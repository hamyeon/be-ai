package com.vintic.backend.product.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// KREAM 체결가로 잰 색상 프리미엄 (#93).
//
// 당근에 (모델, 색상) 버킷이 없을 때의 중간 폴백이다. KREAM은 상품 = 컬러웨이
// 구조라 색상별 체결가가 원래 형태이고, "새제품 시장에서 이 색이 비싸면 중고에서도
// 비싸다"는 가정으로 당근 모델 시세에 프리미엄을 곱한다.
// 산출: crawler/calibration/build_color_premiums.py
//
// 프리미엄이 [0.5, 2.0]을 벗어나는 행은 버린다. 그 정도 차이는 색상이 아니라
// 별개 상품(콜라보·한정판)이 섞였다는 신호다.
@Component
public class ColorPremiumProvider {

    private static final String CSV_PATH = "data/kream_color_premiums.csv";
    private static final double PREMIUM_MIN = 0.5;
    private static final double PREMIUM_MAX = 2.0;

    private final List<ColorPremium> rows = new ArrayList<>();

    public ColorPremiumProvider() {
        load();
    }

    /**
     * 요청한 모델·색상 계열의 KREAM 프리미엄. 없으면 empty - 호출부는 모델 시세만 쓴다.
     */
    public Optional<ColorPremium> find(String brand, String modelName, String color) {
        String requestBrand = normalize(brand);
        String requestModel = normalize(modelName);
        Optional<String> key = ColorFamilies.colorKey(color);
        if (requestBrand.isEmpty() || requestModel.isEmpty() || key.isEmpty()) {
            return Optional.empty();
        }
        return rows.stream()
                .filter(row -> row.brandNorm().equals(requestBrand))
                .filter(row -> row.modelNorm().contains(requestModel)
                        || requestModel.contains(row.modelNorm()))
                .filter(row -> row.colorFamily().equals(key.get()))
                .findFirst();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private void load() {
        try {
            ClassPathResource resource = new ClassPathResource(CSV_PATH);
            if (!resource.exists()) {
                // 프리미엄 파일이 없어도 가격 계산은 모델 시세로 동작한다
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
                    String[] c = line.split(",", -1);
                    if (c.length < 5) {
                        continue;
                    }
                    try {
                        double premium = Double.parseDouble(c[3].trim());
                        if (premium < PREMIUM_MIN || premium > PREMIUM_MAX) {
                            continue;
                        }
                        rows.add(new ColorPremium(
                                normalize(c[0]),
                                normalize(c[1]),
                                c[2].trim(),
                                premium,
                                Integer.parseInt(c[4].trim())
                        ));
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            rows.clear();
        }
    }

    public record ColorPremium(
            String brandNorm,
            String modelNorm,
            String colorFamily,
            double premium,
            int tradeCount
    ) {
    }
}
