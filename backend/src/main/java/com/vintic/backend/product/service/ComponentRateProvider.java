package com.vintic.backend.product.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// 구성품(박스·더스트백·여분끈) 상태별 가격 보정 계수를 제공한다.
//
// 원래 이 값들은 PriceCalculationService에 상수로 박혀 있었고 어디서 나온 숫자인지
// 설명할 수 없었다(FULL 1.00 / PARTIAL 0.97 / NONE 0.95). 상태 등급 계수는 #61에서
// 실측으로 교체했지만 구성품은 그대로 남아 있었다.
//
// 산출 과정은 crawler/calibration/build_component_rates.py에 있다.
// 당근 매물을 (브랜드, 상태등급) 셀로 묶고 그 셀 안에서 구성품 상태별 중앙값 비를 낸다.
//
// CSV의 rate는 "같은 셀에서 구성품이 판정된 매물 전체(ALL) 대비 몇 배"다. 절대 계수가
// 아니다. 기준 모집단이 다른 두 계산 경로가 각각 다르게 환산해 쓴다:
//   - 중고 시세 경로: baseMedian이 구성품 불문 중앙값이므로 rate를 그대로 곱한다
//   - KREAM 경로    : 기준가가 새제품(=풀박스)이므로 rate를 FULL로 나눠 쓴다
//
// 조회 순서: CSV 실측값 -> 코드 기본값
@Component
public class ComponentRateProvider {

    private static final String CSV_PATH = "data/component_rates.csv";

    /** 구성품 불문 전체를 가리키는 키. CSV에 기준선으로 실리지만 요청 값으로는 오지 않는다. */
    public static final String ALL = "ALL";

    /** 구성품 상태를 모를 때. 요청에 componentStatus가 없거나 알 수 없는 값일 때 쓴다. */
    public static final String UNKNOWN = "UNKNOWN";

    // 실측 이전부터 쓰던 값. 실측 데이터가 없는 상태는 이 값을 유지한다.
    //
    // 이 값들은 "풀박스 대비" 절대 계수로 정해진 것이다(FULL = 1.00이 기준).
    // FULL은 실측에서 셀별 편차가 커(상대IQR 0.394, 범위 0.76~2.27) 채택되지 않았다.
    // 표본은 26셀 778건으로 충분했지만, 중앙값 1.379를 그대로 쓰면 풀박스 추천가가
    // 38% 오르고 그 숫자를 설명할 수 없다. 근거 없는 값을 근거 없는 값으로 바꾸는 것보다
    // 기본값을 유지하는 편이 정직하다(#61에서 S·C 등급에 적용한 기준과 같다).
    private static final Map<String, Double> DEFAULT_RATES = Map.of(
            "FULL", 1.00,
            "PARTIAL", 0.97,
            "NONE", 0.95,
            UNKNOWN, 0.97,
            ALL, 1.00
    );

    private static final double FALLBACK_RATE = 0.97;

    private final Map<String, Double> rates = new HashMap<>();
    private final Map<String, Integer> samples = new HashMap<>();

    public ComponentRateProvider() {
        load();
    }

    /**
     * 계수와 그 근거를 함께 돌려준다.
     *
     * <p>실측 계수를 쓴 것과 기본값으로 떨어진 것이 구분되지 않으면, 사용자는 두 값을
     * 같은 신뢰도로 받아들이고 우리도 이번 보정이 어떤 요청에 적용됐는지 알 수 없다.
     * ConditionRateProvider와 같은 이유로 Basis를 함께 돌려준다.
     */
    public ComponentRate resolve(String componentStatus) {
        String status = normalize(componentStatus);
        if (rates.containsKey(status)) {
            return new ComponentRate(rates.get(status), Basis.MEASURED, samples.get(status));
        }
        return new ComponentRate(DEFAULT_RATES.getOrDefault(status, FALLBACK_RATE), Basis.DEFAULT, 0);
    }

    /**
     * 요청한 상태의 계수를 FULL 대비로 환산해 돌려준다.
     *
     * <p>KREAM 경로에서 쓴다. KREAM 기준가는 새제품이라 기준 모집단이 풀박스이므로,
     * ALL 대비 계수를 그대로 곱하면 모든 매물이 풀박스인 것처럼 계산된다.
     *
     * <p>FULL이 실측되지 않았으면(현재 상태) 환산할 기준이 없다. 그때는 기본값 계수를
     * 그대로 쓴다 - 기본값 자체가 이미 풀박스 대비로 정해진 값이라 경로에 맞는다.
     */
    public ComponentRate resolveAgainstFull(String componentStatus) {
        ComponentRate rate = resolve(componentStatus);
        if (rate.basis() != Basis.MEASURED) {
            return rate;
        }
        ComponentRate full = resolve("FULL");
        if (full.basis() != Basis.MEASURED || full.rate() <= 0) {
            // ALL 대비 값을 풀박스 기준가에 그대로 곱할 수는 없다. 환산 기준이 없으면
            // 실측값을 포기하고 기본값으로 떨어진다 - 모집단이 어긋난 계수보다 낫다.
            return new ComponentRate(
                    DEFAULT_RATES.getOrDefault(normalize(componentStatus), FALLBACK_RATE),
                    Basis.DEFAULT,
                    0);
        }
        return new ComponentRate(rate.rate() / full.rate(), Basis.MEASURED, rate.sampleSize());
    }

    private String normalize(String componentStatus) {
        if (componentStatus == null || componentStatus.isBlank()) {
            return UNKNOWN;
        }
        String normalized = componentStatus.trim().toUpperCase();
        // 정의되지 않은 값은 미상으로 본다. 판매자가 임의 문자열을 보내도 계산은 돌아야 한다.
        if (!DEFAULT_RATES.containsKey(normalized)) {
            return UNKNOWN;
        }
        return normalized;
    }

    private void load() {
        try {
            ClassPathResource resource = new ClassPathResource(CSV_PATH);
            if (!resource.exists()) {
                // 실측 파일이 없어도 기본값으로 동작해야 한다. 가격 계산이 멈추면 안 된다.
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                reader.readLine(); // 헤더
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] columns = line.split(",", -1);
                    if (columns.length < 4) {
                        continue;
                    }
                    try {
                        String status = columns[0].trim().toUpperCase();
                        double rate = Double.parseDouble(columns[1].trim());
                        int sample = Integer.parseInt(columns[2].trim());
                        // 계수가 이 범위를 벗어나면 산출이 잘못된 것으로 본다.
                        // ColorPremiumProvider가 [0.5, 2.0]으로 거르는 것과 같은 성격의 가드다.
                        if (rate < 0.5 || rate > 1.5) {
                            continue;
                        }
                        if (!DEFAULT_RATES.containsKey(status)) {
                            continue;
                        }
                        rates.put(status, rate);
                        samples.put(status, sample);
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            // 실측값을 못 읽으면 기본값으로 동작한다. 가격 계산 자체가 실패해서는 안 된다.
            rates.clear();
            samples.clear();
        }
    }

    public enum Basis {
        /** 당근 실거래로 산출한 값 */
        MEASURED,
        /** 실측 표본이 부족하거나 편차가 커서 기존 기본값 사용 */
        DEFAULT
    }

    public record ComponentRate(double rate, Basis basis, int sampleSize) {
    }
}
