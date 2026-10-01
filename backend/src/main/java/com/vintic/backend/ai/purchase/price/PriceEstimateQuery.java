package com.vintic.backend.ai.purchase.price;

import com.vintic.backend.product.domain.Product;

// 시세를 물을 때 넘기는 매물의 조각. PricingRequest와 같은 6개 필드다.
//
// Product에서 바로 만들 수 있게 of()를 두었다. 백엔드 orchestration이 ENGAGE 직전에
// Auction.getProduct()로 부르면 된다.
public record PriceEstimateQuery(
        String brand,
        String model,
        String colorway,
        Integer sizeKr,
        String conditionGrade,
        String componentStatus
) {

    public static PriceEstimateQuery of(Product product) {
        return new PriceEstimateQuery(
                product.getBrand(),
                product.getModel(),
                product.getColorway(),
                product.getSizeKr(),
                product.getConditionGrade(),
                product.getComponentStatus()
        );
    }
}
