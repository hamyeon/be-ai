package com.vintic.backend.ai.purchase.price;

import java.util.Optional;

// Purchase Agent에 AI 시세를 준다. 설계안 5-1 pre-filter("AI 시세 존재")와 5-2 cap·ranking의 입력.
//
// 계약:
//   - 시세를 낼 수 없으면 Optional.empty(). 설계안대로 그 후보는 제외한다 - 시세 없이 cap을
//     hardMaxAmount로 두면 예산 상한까지 무조건 응찰하게 되므로, 틀린 숫자보다 없음이 낫다.
//   - ENGAGE 직전에 서버가 다시 계산한다. Product.recommendedPrice를 읽지 않는다 - 그 값은
//     상품 등록 요청(CreateProductRequest)에 실려 온 클라이언트 값이라 판매자가 부풀릴 수 있고,
//     Agent의 cap이 거기 걸리면 판매자가 구매자의 예산 상한을 끌어올리는 경로가 된다.
//   - 결정적이다. 같은 입력이면 어느 서버에서든 같은 값(설계안 5-2 invariant 7). PricingService
//     캐시(60분 TTL)를 그대로 타므로 scan마다 다시 계산해도 비용은 작다.
public interface PriceEstimateProvider {

    Optional<PriceEstimate> estimate(PriceEstimateQuery query);
}
