package com.vintic.backend.product.pricing;

public record PricingRequest(
        String brand,
        String modelName,
        String color,
        Integer size,
        String conditionGrade,
        String componentStatus
) {

    // 캐시 키. 시세 매칭이 대소문자/공백을 무시하므로("Nike"와 " nike "는 같은 결과),
    // 키도 정규화하지 않으면 같은 입력이 표기만 달라도 캐시를 못 탄다.
    public String cacheKey() {
        return String.join("|",
                normalize(brand), normalize(modelName), normalize(color),
                size == null ? "" : size.toString(),
                normalize(conditionGrade), normalize(componentStatus));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
