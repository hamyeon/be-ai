package com.vintic.backend.product.pricing;

public record PricingRequest(
        String brand,
        String modelName,
        String color,
        Integer size,
        String conditionGrade,
        String componentStatus
) {
}
