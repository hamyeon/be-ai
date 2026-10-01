package com.vintic.backend.autobid.proxy;

// input의 candidates 중 하나에 대한 결과 상태다. 실제 AutoBidSetting에 어떤 도메인 전이 메서드
// (activate/reactivateAfterCapIncrease/markCapReached)를 호출할지는 adapter가 현재 저장된
// 상태를 보고 결정한다 - 이 결과는 "목표 상태"만 말한다.
public record CandidateResult(
        Long userId,
        ProxyEntrantStatus status
) {
}
