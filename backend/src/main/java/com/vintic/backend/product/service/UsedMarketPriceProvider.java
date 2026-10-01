package com.vintic.backend.product.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 모델별 중고 실거래 시세를 제공한다.
//
// 당근마켓·후르츠패밀리 매물에서 산출한 값이다(crawler/calibration/build_used_market_prices.py).
// 우리 서비스는 중고 경매라, 같은 모델의 중고 실거래가 있으면 "새제품가 x 상태계수"라는
// 추정보다 이쪽이 곧은 근거다(#86). 추천가 계산에서 이 시세가 1순위이고, 매칭이 없으면
// 기존 KREAM/eBay 방식으로 넘어간다.
//
// 알려진 한계: 당근 가격은 호가이지 체결가가 아니다. 안 팔린 매물도 섞여 있으므로
// 실제 체결가는 이보다 낮을 수 있다. 중앙값과 IQR을 함께 제공해 분포를 숨기지 않는다.
@Component
public class UsedMarketPriceProvider {

    private static final String CSV_PATH = "data/used_market_prices.csv";
    private static final String COLOR_CSV_PATH = "data/used_market_prices_by_color.csv";

    private final List<UsedMarketPrice> rows = new ArrayList<>();
    private final List<ColorPrice> colorRows = new ArrayList<>();

    public UsedMarketPriceProvider() {
        load();
        loadColors();
    }

    /**
     * 요청한 브랜드·모델의 중고 시세. 없으면 empty - 호출부는 기존 방식으로 폴백한다.
     */
    public Optional<UsedMarketPrice> find(String brand, String modelName) {
        String requestBrand = normalize(brand);
        String requestModel = normalize(modelName);
        if (requestBrand.isEmpty() || requestModel.isEmpty()) {
            return Optional.empty();
        }
        // 표기가 흔들려도 매칭되게 양방향 포함을 본다("Jordan 1 Retro High" <-> "jordan1").
        // 브랜드는 정확히 같아야 한다 - "574"가 다른 브랜드 요청에 붙으면 안 된다.
        return rows.stream()
                .filter(row -> row.brandNorm().equals(requestBrand))
                .filter(row -> row.modelNorm().contains(requestModel)
                        || requestModel.contains(row.modelNorm()))
                .findFirst();
    }

    /**
     * 같은 모델의 "같은 색상 계열" 시세 (#93). 색상 표기는 계열로 정규화해 비교하므로
     * "Wolf Gray"/"그레이"/"회색"이 전부 grey 버킷에 붙는다.
     * 색상 버킷이 없으면 empty - 호출부는 모델 시세로 폴백한다(정확도가 나빠지는 경로 없음).
     */
    public Optional<ColorPrice> findColor(String brand, String modelName, String color) {
        String requestBrand = normalize(brand);
        String requestModel = normalize(modelName);
        Optional<String> key = ColorFamilies.colorKey(color);
        if (requestBrand.isEmpty() || requestModel.isEmpty() || key.isEmpty()) {
            return Optional.empty();
        }
        return colorRows.stream()
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
                // 시세 파일이 없어도 가격 계산은 기존 방식으로 돌아야 한다
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
                    if (c.length < 7) {
                        continue;
                    }
                    try {
                        rows.add(new UsedMarketPrice(
                                c[0].trim(),
                                normalize(c[0]),
                                normalize(c[1]),
                                c[2].trim(),
                                Integer.parseInt(c[3].trim()),
                                Integer.parseInt(c[4].trim()),
                                Integer.parseInt(c[5].trim()),
                                Integer.parseInt(c[6].trim())
                        ));
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            // 읽기 실패 = 중고 시세 없음으로 동작. 가격 계산 자체는 실패하면 안 된다.
            rows.clear();
        }
    }

    // 색상 CSV도 파일이 없거나 깨져도 색상 시세만 포기하고 동작한다
    private void loadColors() {
        try {
            ClassPathResource resource = new ClassPathResource(COLOR_CSV_PATH);
            if (!resource.exists()) {
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
                    if (c.length < 7) {
                        continue;
                    }
                    try {
                        colorRows.add(new ColorPrice(
                                normalize(c[0]),
                                normalize(c[1]),
                                c[2].trim(),
                                Integer.parseInt(c[3].trim()),
                                Integer.parseInt(c[4].trim()),
                                Integer.parseInt(c[5].trim()),
                                Integer.parseInt(c[6].trim())
                        ));
                    } catch (NumberFormatException e) {
                        // 한 줄이 깨져도 나머지는 읽는다
                    }
                }
            }
        } catch (Exception e) {
            colorRows.clear();
        }
    }

    public record UsedMarketPrice(
            String brand,
            String brandNorm,
            String modelNorm,
            String modelDisplay,
            int listingCount,
            int medianPrice,
            int q1Price,
            int q3Price
    ) {
    }

    /** (모델, 색상 계열) 시세 버킷. 표본이 서는 버킷만 CSV에 실린다(#93). */
    public record ColorPrice(
            String brandNorm,
            String modelNorm,
            String colorFamily,
            int listingCount,
            int medianPrice,
            int q1Price,
            int q3Price
    ) {
    }
}
