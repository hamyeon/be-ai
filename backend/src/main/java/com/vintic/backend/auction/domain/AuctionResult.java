package com.vintic.backend.auction.domain;

// FINAL contract §10. Result는 별도 persisted entity가 아니다(#56-0 확정) - Auction/Order/
// (향후) BackupOffer/UserPenalty 상태를 조합해 매 조회 시점에 계산한다. #56-1은 Order 도메인만
// 있어 BACKUP_WAITING/FORFEITED로 이어지는 데이터 소스(BackupOffer/Penalty)가 아직 없다 -
// 두 값은 enum에 미리 선언만 해 두고, 실제로 계산되는 경로는 #56-2에서 추가한다
// (AuctionResultQueryService 클래스 주석 참고).
public enum AuctionResult {
    NO_BIDS,
    WON,
    LOST,
    BACKUP_WAITING,
    FORFEITED,
    PAYMENT_EXPIRED
}
