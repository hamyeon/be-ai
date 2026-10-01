package com.vintic.backend.ai.purchase.price;

import java.time.LocalDateTime;

// Purchase Agent가 cap·ranking에 쓰는 AI 시세. 설계안 5-2의 aiEstimatedPrice.
//
// estimatedPrice: 추천가(실거래 중앙값 × 상태 비율 × 구성품 반영률). 설계안의 aiEstimatedPrice.
// lowerBound/upperBound: 같은 계산을 실거래 IQR(25~75%)의 양 끝에 적용한 값. "매물의 절반이 이
//   구간에 있다"는 뜻이라, cap을 보수적으로 잡고 싶으면 lowerBound를 쓰면 된다(팀 결정).
// source: 어느 근거로 계산됐는가. USED_MARKET(당근·후르츠 실거래)이 1순위, KREAM(새제품가 ×
//   상태 계수)이 2순위. 시세 없음은 Optional.empty()로 표현하고 여기 오지 않는다.
// sampleCount: USED_MARKET일 때 근거 매물 수. KREAM이면 0. 표본이 얇은 시세를 걸러내는 데 쓴다.
// reason: 사람이 읽는 근거 문구. PricingService가 만든 그대로.
// computedAt: 계산 시각. 시세 CSV는 배포 때 바뀌고 캐시 TTL이 60분이라, 언제 값인지 남긴다.
public record PriceEstimate(
        int estimatedPrice,
        int lowerBound,
        int upperBound,
        Source source,
        int sampleCount,
        String reason,
        LocalDateTime computedAt
) {

    public enum Source {
        USED_MARKET,
        KREAM
    }
}
