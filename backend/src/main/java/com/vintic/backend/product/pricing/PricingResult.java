package com.vintic.backend.product.pricing;

import java.util.List;

public record PricingResult(
        int recommendedPrice,
        int baseMarketPrice,
        int kreamAveragePrice,
        int ebayAveragePrice,
        int minRecommendedPrice,
        int maxRecommendedPrice,
        String priceRange,
        String reason,
        List<MatchedMarketPrice> kreamMatches,
        List<MatchedMarketPrice> ebayMatches
) {
    public record MatchedMarketPrice(
            String source,
            String brand,
            String modelName,
            String color,
            Integer size,
            String conditionGrade,
            String componentStatus,
            int price,
            String url
    ) {
    }
}
