package com.vintic.backend.auction.audit;

// 가격/승자 resolution을 유발한 사용자 command의 종류다. SYSTEM_OPEN(SCHEDULED->LIVE 시작 정산,
// ProxyTrigger.None)은 아직 production 호출부가 없어 값만 선언해두고 실제로 기록되지 않는다
// (docs/api/auction-api-contract-gap.md의 DEFERRED UNTIL LIFECYCLE INTEGRATION 참고).
public enum PriceAuditTrigger {
    MANUAL_BID,
    AUTO_BID_CREATE,
    AUTO_BID_UPDATE,
    SYSTEM_OPEN
}
