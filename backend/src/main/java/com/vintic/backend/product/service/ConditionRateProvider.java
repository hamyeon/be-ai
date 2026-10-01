package com.vintic.backend.product.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// 상태 등급별 가격 보정 계수를 제공한다.
//
// 원래 이 값들은 코드에 상수로 박혀 있었고 어디서 나온 숫자인지 설명할 수 없었다.
// KREAM/eBay는 새제품 시세라 거기에 계수를 곱해 중고가를 추정하는 구조인데,
// 그 계수가 측정된 값이 아니었다.
//
// 당근 실거래 매물과 KREAM 참조를 대조해 실측한 값이 있으면 그걸 쓴다.
// 산출 과정은 crawler/calibration/build_condition_rates.py에 있다.
//
// 조회 순서: (모델, 등급) -> (공통, 등급) -> 코드 기본값
//
// 모델별 계수를 따로 두는 이유는 감가 속도가 모델마다 다르기 때문이다.
// 에어포스1은 상시 대량 생산이라 정가의 0.39지만 993은 0.60이다.
// 통합 계수 하나로 뭉개면 993이 31% 낮게 추천된다. (실측)
@Component
public class ConditionRateProvider {

    private static final String CSV_PATH = "data/condition_rates.csv";

    // 실측 이전부터 쓰던 값. 실측 데이터가 없는 등급은 이 값을 유지한다.
    // S(표본 12건)와 C(표본 7건)는 표본이 부족해 아직 기본값이다. 부족한 표본의
    // 중앙값으로 바꾸면 근거 없는 값을 근거 없는 값으로 바꾸는 것뿐이다.
    // ALL은 상태 불문 전체 매물의 계수로, 중고 시세 경로의 기준선이다.
    // CSV가 없을 때는 UNKNOWN 기본값과 같게 둬서 비율이 기존과 같이 동작한다.
    private static final Map<String, Double> DEFAULT_RATES = Map.of(
            "DS", 0.80,
            "S", 0.70,
            "A", 0.60,
            "B", 0.40,
            "C", 0.20,
            "UNKNOWN", 0.60,
            "ALL", 0.60
    );

    private static final double FALLBACK_RATE = 0.60;

    // key: "모델키|등급". 모델 공통은 모델키가 빈 문자열이다.
    private final Map<String, Double> rates = new HashMap<>();
    private final Map<String, Integer> samples = new HashMap<>();

    public ConditionRateProvider() {
        load();
    }

    /**
     * 계수와 그 근거를 함께 돌려준다.
     *
     * <p>실측 계수를 쓴 것과 기본값으로 떨어진 것이 구분되지 않으면, 사용자는 두 값을
     * 같은 신뢰도로 받아들이고 우리도 이번 보정이 어떤 요청에 적용됐는지 알 수 없다.
     */
    public ConditionRate resolve(String modelName, String conditionGrade) {
        String grade = normalizeGrade(conditionGrade);
        String modelKey = normalizeModel(modelName);

        // 1) 이 모델 전용 실측값
        String modelSpecific = findModelKey(modelKey, grade);
        if (modelSpecific != null) {
            return new ConditionRate(rates.get(modelSpecific), Basis.MEASURED_MODEL,
                    samples.get(modelSpecific));
        }

        // 2) 전 모델 공통 실측값
        String common = "|" + grade;
        if (rates.containsKey(common)) {
            return new ConditionRate(rates.get(common), Basis.MEASURED_COMMON, samples.get(common));
        }

        // 3) 실측 이전부터 쓰던 값
        return new ConditionRate(DEFAULT_RATES.getOrDefault(grade, FALLBACK_RATE), Basis.DEFAULT, 0);
    }

    // CSV의 모델 키와 요청의 모델명은 표기가 다를 수 있다("Air Force 1 Low" vs "에어포스1").
    // findMatches와 같은 방식으로 양방향 포함을 본다.
    private String findModelKey(String modelKey, String grade) {
        if (modelKey.isEmpty()) {
            return null;
        }
        for (String key : rates.keySet()) {
            int separator = key.indexOf('|');
            String csvModel = key.substring(0, separator);
            if (csvModel.isEmpty() || !key.endsWith("|" + grade)) {
                continue;
            }
            if (csvModel.contains(modelKey) || modelKey.contains(csvModel)) {
                return key;
            }
        }
        return null;
    }

    private String normalizeGrade(String conditionGrade) {
        if (conditionGrade == null || conditionGrade.isBlank()) {
            return "UNKNOWN";
        }
        return conditionGrade.trim().toUpperCase();
    }

    private String normalizeModel(String modelName) {
        if (modelName == null) {
            return "";
        }
        return modelName.toLowerCase().replaceAll("[^a-z0-9]", "");
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
                        String model = normalizeModel(columns[0]);
                        String grade = columns[1].trim().toUpperCase();
                        double rate = Double.parseDouble(columns[2].trim());
                        int sample = Integer.parseInt(columns[3].trim());
                        // 계수가 0 이하이거나 1을 크게 넘으면 산출이 잘못된 것으로 본다.
                        if (rate <= 0 || rate > 1.5) {
                            continue;
                        }
                        rates.put(model + "|" + grade, rate);
                        samples.put(model + "|" + grade, sample);
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
        /** 이 모델의 실거래로 산출 */
        MEASURED_MODEL,
        /** 여러 모델을 묶어 산출한 공통값 */
        MEASURED_COMMON,
        /** 실측 표본이 부족해 기존 기본값 사용 */
        DEFAULT
    }

    public record ConditionRate(double rate, Basis basis, int sampleSize) {
    }
}
