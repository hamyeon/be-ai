package com.vintic.backend.product.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarketPriceDataLoader {

    private static final String KREAM_CSV_PATH = "data/kream_normalized.csv";
    private static final String EBAY_CSV_PATH = "data/ebay_normalized.csv";

    // 한국 신발 사이즈로 볼 수 있는 범위. 유아용부터 특대까지 넉넉히 잡는다.
    private static final int MIN_SIZE_KR = 150;
    private static final int MAX_SIZE_KR = 350;

    // "200(US 1.5)"에서 앞의 200만 읽기 위한 패턴
    private static final Pattern LEADING_NUMBER = Pattern.compile("\\d+");

    public List<MarketPriceRow> loadKreamRows() {
        return loadRows(KREAM_CSV_PATH, "KREAM");
    }

    public List<MarketPriceRow> loadEbayRows() {
        return loadRows(EBAY_CSV_PATH, "EBAY");
    }

    private List<MarketPriceRow> loadRows(String path, String source) {
        try {
            ClassPathResource resource = new ClassPathResource(path);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String headerLine = reader.readLine();

                if (headerLine == null || headerLine.isBlank()) {
                    return List.of();
                }

                List<String> headers = parseCsvLine(headerLine);
                List<MarketPriceRow> rows = new ArrayList<>();

                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }

                    List<String> values = parseCsvLine(line);

                    MarketPriceRow row;
                    if ("KREAM".equals(source)) {
                        row = mapKreamRow(headers, values);
                    } else {
                        row = mapEbayRow(headers, values);
                    }

                    if (row != null) {
                        rows.add(row);
                    }
                }

                return rows;
            }
        } catch (Exception e) {
            throw new IllegalStateException(source + " CSV 파일을 읽는 중 오류가 발생했습니다.", e);
        }
    }

    private MarketPriceRow mapKreamRow(List<String> headers, List<String> values) {
        String brand = getValue(headers, values, "브랜드");
        String model = getValue(headers, values, "모델명");
        String colorway = getValue(headers, values, "컬러웨이");
        String sizeKrText = getValue(headers, values, "한국 사이즈");
        String priceText = getValue(headers, values, "KREAM 가격");
        String conditionGrade = getValue(headers, values, "상태");
        String url = getValue(headers, values, "상품 URL");

        Integer sizeKr = parseSizeKr(sizeKrText);
        Integer price = parseInteger(priceText);

        if (isBlank(brand) || isBlank(model) || isBlank(colorway) || sizeKr == null || price == null) {
            return null;
        }

        return new MarketPriceRow(
                "KREAM",
                brand,
                model,
                colorway,
                sizeKr,
                conditionGrade,
                null,
                price,
                url
        );
    }

    private MarketPriceRow mapEbayRow(List<String> headers, List<String> values) {
        String brand = getValue(headers, values, "brand");
        String model = getValue(headers, values, "model");
        String colorway = getValue(headers, values, "colorway");
        String sizeKrText = getValue(headers, values, "size_kr");
        String priceText = getValue(headers, values, "ebay_price_krw");
        String conditionGrade = getValue(headers, values, "condition_grade");
        String boxIncludedText = getValue(headers, values, "box_included");
        String url = getValue(headers, values, "item_url");

        Integer sizeKr = parseSizeKr(sizeKrText);
        Integer price = parseInteger(priceText);
        Boolean boxIncluded = parseBoolean(boxIncludedText);

        if (isBlank(brand) || isBlank(model) || isBlank(colorway) || sizeKr == null || price == null) {
            return null;
        }

        return new MarketPriceRow(
                "EBAY",
                brand,
                model,
                colorway,
                sizeKr,
                conditionGrade,
                boxIncluded,
                price,
                url
        );
    }

    private String getValue(List<String> headers, List<String> values, String columnName) {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).trim().equals(columnName)) {
                if (i < values.size()) {
                    return values.get(i).trim();
                }
                return null;
            }
        }

        return null;
    }

    private Integer parseInteger(String value) {
        try {
            if (isBlank(value)) {
                return null;
            }

            String onlyNumber = value.replaceAll("[^0-9]", "");

            if (onlyNumber.isBlank()) {
                return null;
            }

            return Integer.parseInt(onlyNumber);
        } catch (Exception e) {
            return null;
        }
    }

    // 한국 사이즈를 읽는다. parseInteger를 그대로 쓰면 안 된다.
    //
    // KREAM CSV에는 "200(US 1.5)", "190(13K)"처럼 괄호로 해외 사이즈를 병기한 행이 있다.
    // 숫자만 남기는 방식으로는 20015, 19013이 되어 어떤 요청과도 매칭되지 않는다.
    // 값이 틀리게 나오는 게 아니라 그 행이 조용히 없는 것처럼 동작한다.
    // (실측: KREAM 75행 중 5행이 해당하고 전부 Samba OG였다)
    //
    // 그래서 괄호 앞의 첫 숫자만 읽고, 신발 사이즈로 볼 수 없는 값은 버린다.
    private Integer parseSizeKr(String value) {
        if (isBlank(value)) {
            return null;
        }

        Matcher matcher = LEADING_NUMBER.matcher(value.trim());
        if (!matcher.find()) {
            return null;
        }

        try {
            int size = Integer.parseInt(matcher.group());
            // 신발 사이즈 범위를 벗어나면 표기가 깨진 것으로 보고 버린다.
            if (size < MIN_SIZE_KR || size > MAX_SIZE_KR) {
                return null;
            }
            return size;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean parseBoolean(String value) {
        if (isBlank(value)) {
            return null;
        }

        String normalized = value.trim().toLowerCase();

        if ("true".equals(normalized)) {
            return true;
        }

        if ("false".equals(normalized)) {
            return false;
        }

        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private List<String> parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        result.add(current.toString());
        return result;
    }

    public record MarketPriceRow(
            String source,
            String brand,
            String model,
            String colorway,
            Integer sizeKr,
            String conditionGrade,
            Boolean boxIncluded,
            int price,
            String url
    ) {
    }
}