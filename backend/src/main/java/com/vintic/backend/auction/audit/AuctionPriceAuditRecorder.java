package com.vintic.backend.auction.audit;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.user.domain.User;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

// persistence/application boundary 전용 - ProxyPriceEngine(순수 계산)은 이 클래스의 존재를 모른다.
// BidCommandService/AutoBidCommandService가 Proxy resolution까지 전부 반영한 뒤, 같은 트랜잭션
// 안에서 이 레코더를 딱 한 번만 호출한다(호출자가 "커맨드당 최대 1회"를 보장) - Proxy 내부에서
// 파생 응찰이 몇 개 생기든 그 전부를 반영한 auction의 최종 상태 하나만 기록 대상이다.
@Component
public class AuctionPriceAuditRecorder {

    private final AuctionPriceAuditRepository repository;
    private final Clock clock;

    public AuctionPriceAuditRecorder(AuctionPriceAuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    // beforePrice/beforeWinnerId는 이 command가 Auction을 건드리기 "전" 상태를 호출자가 캡처해
    // 넘긴다 - 이 클래스는 auction의 현재(이미 반영된) 상태만 알 수 있어 스스로는 알아낼 수 없다.
    //
    // resolutionPriceChanged는 ProxyResolution.priceChanged()를 그대로 전달받는다 - MANUAL_BID의
    // 경우 engine 내부 기준(manual 금액 M)과 이 메서드의 beforePrice(커맨드 시작 전, M보다 항상
    // 낮음)가 다르기 때문에 필요하다. M 자체는 이미 auction.placeManualBid()로 반영된 뒤 engine을
    // 호출하므로, "M에서 더 올랐는가"(MANUAL_OVERTAKEN_BY_AUTO)와 "M 그대로인가"(TIE_FIRST_IN_WINS)를
    // 구분하려면 overall beforePrice/afterPrice 비교가 아니라 engine 자신의 판단이 필요하다.
    // AUTO_BID_CREATE/UPDATE는 beforePrice가 곧 engine의 입력 currentPrice와 같아 두 값이 항상
    // 일치하므로 이 구분이 필요 없다(그래도 인자를 통일해서 받는다).
    //
    // priceChanged(overall)든 winnerChanged든 하나라도 참이면 기록한다 - 둘 다 거짓인 순수 no-op에는
    // 기록하지 않는다(예: 경쟁자 없는 LIVE 등록으로 ACTIVE만 되고 가격/승자 변화가 없는 경우).
    public void recordIfChanged(
            Auction auction,
            PriceAuditTrigger trigger,
            Long entrantUserId,
            Long beforePrice,
            Long beforeWinnerId,
            boolean resolutionPriceChanged,
            Long idempotencyId
    ) {
        Long afterPrice = auction.getCurrentPrice();
        User afterWinner = auction.getCurrentWinner();
        Long afterWinnerId = afterWinner == null ? null : afterWinner.getId();

        boolean priceChanged = !Objects.equals(beforePrice, afterPrice);
        boolean winnerChanged = !Objects.equals(beforeWinnerId, afterWinnerId);
        if (!priceChanged && !winnerChanged) {
            return;
        }

        PriceAuditRule rule = classify(trigger, entrantUserId, afterWinnerId, resolutionPriceChanged);
        BidType bidType = resolveBidType(trigger, entrantUserId, afterWinnerId);

        AuctionPriceAudit audit = AuctionPriceAudit.record(
                auction, beforePrice, afterPrice, afterWinner, trigger, bidType, rule,
                idempotencyId, LocalDateTime.now(clock)
        );
        repository.save(audit);
    }

    // MANUAL_BID: entrant가 최종 승자면 경쟁 없이 이긴 것(가격이 그대로 manual 금액이었다는 뜻이다 -
    // manual phantom은 항상 실제 candidate보다 늦게 등록된 것으로 취급돼 tie에서도 지므로, entrant가
    // 이겼다는 것 자체가 "경쟁에서 진 AutoBid가 없거나 이길 수 없었다"를 의미한다). 아니라면 가격이
    // 실제로 올랐는지(OVERTAKEN)와 그대로인지(TIE_FIRST_IN_WINS)로 나뉜다.
    // AUTO_BID_CREATE/UPDATE: entrant가 이겼는지 아닌지만으로 나뉜다 - 이 트리거에서 발생하는
    // resultingAutoBid는 항상 실제 AutoBid이므로(§0.13, manual 개념이 없음) bidType은 항상 AUTO다.
    private PriceAuditRule classify(PriceAuditTrigger trigger, Long entrantUserId, Long afterWinnerId, boolean priceChanged) {
        boolean entrantWins = entrantUserId.equals(afterWinnerId);
        if (trigger == PriceAuditTrigger.MANUAL_BID) {
            if (entrantWins) {
                return PriceAuditRule.MANUAL_UNCONTESTED;
            }
            return priceChanged ? PriceAuditRule.MANUAL_OVERTAKEN_BY_AUTO : PriceAuditRule.TIE_FIRST_IN_WINS;
        }
        return entrantWins ? PriceAuditRule.AUTO_ENTRANT_WINS : PriceAuditRule.AUTO_INCUMBENT_DEFENDS;
    }

    private BidType resolveBidType(PriceAuditTrigger trigger, Long entrantUserId, Long afterWinnerId) {
        if (trigger == PriceAuditTrigger.MANUAL_BID && entrantUserId.equals(afterWinnerId)) {
            return BidType.MANUAL;
        }
        return BidType.AUTO;
    }
}
