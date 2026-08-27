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
// 당근 실거래 매물과 KREAM 참조를 대조해 실측한 값이 있으면 그걸 쓰고, 없으면 기존
// 기본값으로 돌아간다. 산출 과정은 crawler/calibration/build_condition_rates.py에 있다.
//
// 실측값을 CSV로 뺀 이유는 재수집으로 표본이 늘면 파일만 갈아끼우면 되기 때문이다.
// 표본이 부족한 등급은 CSV에 아예 넣지 않는다. 12건짜리 중앙값으로 계수를 바꾸면
// 근거 없는 값을 근거 없는 값으로 바꾸는 것뿐이다.
@Component
public class ConditionRateProvider {

    private static final String CSV_PATH = "data/condition_rates.csv";

    // 실측 이전부터 쓰던 값. 실측 데이터가 없는 등급은 이 값을 유지한다.
    private static final Map<String, Double> DEFAULT_RATES = Map.of(
            "DS", 0.80,
            "S", 0.70,
            "A", 0.60,
            "B", 0.40,
            "C", 0.20,
            "UNKNOWN", 0.60
    );

    private static final double FALLBACK_RATE = 0.60;

    private final Map<String, Double> measuredRates;
    private final Map<String, Integer> sampleSizes;

    public ConditionRateProvider() {
        this.measuredRates = new HashMap<>();
        this.sampleSizes = new HashMap<>();
        load();
    }

    /**
     * 해당 등급의 보정 계수. 실측값이 있으면 실측값, 없으면 기본값.
     */
    public double rateOf(String conditionGrade) {
        String grade = normalize(conditionGrade);
        Double measured = measuredRates.get(grade);
        if (measured != null) {
            return measured;
        }
        return DEFAULT_RATES.getOrDefault(grade, FALLBACK_RATE);
    }

    /**
     * 이 등급의 계수가 실측에서 나왔는지. 응답에 근거를 표시하는 데 쓴다.
     *
     * <p>실측 계수를 쓴 것과 기본값으로 떨어진 것이 구분되지 않으면,
     * 이번 작업이 실제로 어떤 요청에 영향을 줬는지 알 수 없다.
     */
    public boolean isMeasured(String conditionGrade) {
        return measuredRates.containsKey(normalize(conditionGrade));
    }

    /**
     * 실측 계수의 표본 수. 실측이 아니면 0.
     */
    public int sampleSizeOf(String conditionGrade) {
        return sampleSizes.getOrDefault(normalize(conditionGrade), 0);
    }

    private String normalize(String conditionGrade) {
        if (conditionGrade == null || conditionGrade.isBlank()) {
            return "UNKNOWN";
        }
        return conditionGrade.trim().toUpperCase();
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
                    if (columns.length < 3) {
                        continue;
                    }
                    try {
                        String grade = columns[0].trim().toUpperCase();
                        double rate = Double.parseDouble(columns[1].trim());
                        int sampleSize = Integer.parseInt(columns[2].trim());
                        // 계수가 0 이하이거나 1을 크게 넘으면 산출이 잘못된 것으로 본다.
                        if (rate <= 0 || rate > 1.5) {
                            continue;
                        }
                        measuredRates.put(grade, rate);
                        sampleSizes.put(grade, sampleSize);
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            // 실측값을 못 읽으면 기본값으로 동작한다. 가격 계산 자체가 실패해서는 안 된다.
            measuredRates.clear();
            sampleSizes.clear();
        }
    }
}
