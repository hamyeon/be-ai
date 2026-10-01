package com.vintic.backend.autobid.proxy;

import java.time.LocalDateTime;

// 경쟁 후보 1명(=현재 CANCELED가 아닌 AutoBidSetting 1건)을 나타내는 순수 데이터다. Entity를
// 직접 넘기지 않는다 - 호출부(adapter)가 AutoBidSetting에서 필요한 값만 뽑아 구성한다.
//
// maxAmount는 정렬(bidIncrement 배수) 여부와 무관하게 저장된 원본값 그대로 넘긴다 - effectiveCap은
// 엔진이 내부에서 계산한다(EffectiveCapCalculator, AutoBidSetting.getEffectiveCap()과 동일 공식).
//
// registeredAt/id는 FIRST-IN WINS tie-break에 쓰인다(§0.12) - 실제 DB ordering key(생성시각
// 정밀도/auto-increment 등)는 이번에도 확정하지 않고, 호출부가 이미 갖고 있는 값(createdAt, id)을
// 그대로 넘겨받는 형태로만 설계한다. id는 아직 저장되지 않은 신규 entrant의 경우 null일 수 있다 -
// 이 경우 tie-break에서 항상 "나중"(짐)으로 취급한다.
public record ProxyCandidate(
        Long userId,
        Long maxAmount,
        LocalDateTime registeredAt,
        Long id
) {
    public ProxyCandidate {
        if (userId == null) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        if (maxAmount == null || maxAmount <= 0) {
            throw new IllegalArgumentException("maxAmount는 0보다 커야 합니다.");
        }
        if (registeredAt == null) {
            throw new IllegalArgumentException("registeredAt은 필수입니다.");
        }
    }
}
