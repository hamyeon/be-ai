package com.vintic.backend.auction.audit;

// resolution이 어떤 §0.13 규칙 경로로 결정됐는지를 구분한다. entrant는 이 command의 행위자
// (manual bidder, 또는 AutoBid CREATE/UPDATE의 userId)다.
public enum PriceAuditRule {
    // MANUAL_BID: entrant(manual bidder)가 경쟁 없이 그대로 이김 - 경쟁자가 없거나
    // 경쟁자의 effectiveCap이 manual 금액보다 낮아 이길 수 없었던 경우 모두 포함한다.
    MANUAL_UNCONTESTED,
    // MANUAL_BID: 경쟁 AutoBid의 effectiveCap이 manual 금액보다 높아 즉시 반격해 가격이 올랐다.
    MANUAL_OVERTAKEN_BY_AUTO,
    // MANUAL_BID: 경쟁 AutoBid의 effectiveCap이 manual 금액과 정확히 같아 가격은 그대로지만
    // FIRST-IN WINS로 승자만 그 AutoBid로 되돌아갔다(priceChanged=false && winnerChanged=true).
    TIE_FIRST_IN_WINS,
    // AUTO_BID_CREATE/UPDATE: entrant 자신이 최종 승자가 됐다.
    AUTO_ENTRANT_WINS,
    // AUTO_BID_CREATE/UPDATE: entrant의 등록/수정이 다른 기존 참가자의 가격/승자 결과를
    // 바꿔놓았지만(재경쟁 유발), 최종 승자는 entrant가 아니다.
    AUTO_INCUMBENT_DEFENDS
}
