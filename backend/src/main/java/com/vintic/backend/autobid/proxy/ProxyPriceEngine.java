package com.vintic.backend.autobid.proxy;

import com.vintic.backend.autobid.domain.EffectiveCapCalculator;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 순수 deterministic calculation이다: repository 조회 없음, entity save 없음, transaction 없음,
// lock 획득 없음, 현재 시간 조회 없음, 외부 API 호출 없음. 같은 ProxyResolutionInput은 항상 같은
// ProxyResolution을 반환한다. DB에서 후보를 모으고 결과를 실제 Auction/AutoBidSetting/Bid에
// 반영하는 것은 호출부(AutoBidCommandService/BidCommandService)의 책임이다.
//
// 세 트리거(NONE/MANUAL/AUTO)를 하나의 "field(경쟁장)" 모델로 통일한다 - field는 실제 candidate들과
// 최대 1개의 "phantom"(실체 없는 고정 ceiling)으로 구성된다.
//   MANUAL - phantom은 항상 존재한다: 방금 반영된 manual bid 금액(M), 소유자는 manual bidder.
//   AUTO   - currentWinnerUserId가 있고 그 사용자가 candidates에 없으면(=AutoBid로 뒷받침되지
//            않는 최고입찰자) phantom이 존재한다: 금액은 currentPrice, 더 늘어나지 않는다.
//   NONE   - candidates가 1개 이상이면 phantom이 존재한다: 금액은 currentPrice 그 자체 -
//            이것이 "예약자 1명도 최소 한 단계는 응찰해야 한다"(§0.13)를 만들어내는 장치다.
// phantom은 실제 candidate보다 항상 늦게 등록된 것으로 취급해 tie-break에서 항상 진다
// (LocalDateTime.MAX 사용 - Manual의 "방금 들어온 입찰"이 기존 AutoBid에게 지는 것과 동일한
// 원리를, AUTO/NONE의 phantom에도 방어적으로 동일하게 적용한다. 실제로는 실제 candidate의
// effectiveCap이 phantom의 고정값보다 항상 커서 tie 자체가 수학적으로 발생하지 않는다).
@Service
public class ProxyPriceEngine {

    private static final Comparator<Field> FIELD_ORDER = Comparator
            .comparingLong((Field f) -> f.cap).reversed()
            .thenComparing(f -> f.tieBreakAt)
            .thenComparingLong(f -> f.tieBreakId == null ? Long.MAX_VALUE : f.tieBreakId);

    public ProxyResolution resolve(ProxyResolutionInput input) {
        List<Field> field = buildField(input);
        Long unchangedWinner = defaultWinner(input.trigger());
        List<Field> realCandidates = field.stream().filter(f -> !f.phantom).toList();

        if (realCandidates.isEmpty()) {
            return new ProxyResolution(input.currentPrice(), unchangedWinner, false, null, false, List.of());
        }

        field = new ArrayList<>(field);
        field.sort(FIELD_ORDER);
        Field top = field.get(0);

        // AUTO/NONE의 phantom은 실제 candidate의 effectiveCap보다 항상 작아(등록 시 maxAmount >=
        // minCapAmount = currentPrice + bidIncrement가 보장되므로) top이 될 수 없다. 하지만 MANUAL의
        // phantom(=방금 반영된 manual bid 금액 M)은 다르다 - 경쟁하는 실제 AutoBid가 없거나 전부
        // effectiveCap < M이면 top이 phantom(M) 자신이 되는 게 정상이다(그대로 아래 공식에 태운다 -
        // 이 경우 finalPrice는 항상 M으로 계산돼 priceChanged=false, winner=manual bidder가 되고,
        // 각 실제 candidate는 자신의 cap과 M을 비교해 정확한 ACTIVE/CAP_REACHED를 받는다).
        Field second = field.size() > 1 ? field.get(1) : null;
        if (second == null) {
            // 이길 상대가 없다 - 자기 cap을 올리는 것만으로 스스로에게 응찰시키지 않는다(§0.13,
            // "entrant 자신이 currentWinner인 경우 cap 수정만으로 자기 가격을 스스로 올리지 않음").
            return new ProxyResolution(input.currentPrice(), unchangedWinner, false, null, false,
                    activeResultsOnly(realCandidates));
        }

        long finalPrice = Math.max(input.currentPrice(), Math.min(top.cap, second.cap + input.bidIncrement()));
        boolean priceChanged = finalPrice != input.currentPrice();
        // winner는 항상 field 1위(top)다 - price가 그대로여도(동률 FIRST-IN WINS) 실제로 경쟁이
        // 있었다면(second가 존재) top이 승자다. unchangedWinner(트리거 당사자)로 그대로 두면 안 된다 -
        // 예: MANUAL과 동률인 기존 AutoBid는 가격을 올리지 않고도(§0.13 case4) 승자 자리를 되찾아야
        // 한다. "경쟁 상대가 아예 없는" second==null 분기만 unchangedWinner를 쓴다(위에서 별도 처리).
        Long finalWinner = top.userId;
        boolean winnerChanged = !finalWinner.equals(unchangedWinner);

        // 가격이 그대로여도(동률) winner가 바뀌었다면 실제로 AutoBid가 반격해 승자 자리를 되찾은
        // 것이다 - 그 AutoBid의 Bid row는 반드시 저장돼야 한다(priceChanged만 보고 건너뛰면 안 됨).
        ResultingAutoBid resultingAutoBid = (priceChanged || winnerChanged) ? new ResultingAutoBid(top.userId, finalPrice) : null;
        boolean proxyResponded = (priceChanged || winnerChanged) && input.trigger() instanceof ProxyTrigger.Manual;

        List<CandidateResult> results = new ArrayList<>();
        for (Field f : realCandidates) {
            boolean isWinner = f.userId.equals(top.userId);
            // ACTIVE: 현재 winner이거나, cap이 finalPrice를 "초과"해 아직 여유가 있음(단순히
            // finalPrice와 같기만 한 건 CAP_REACHED다 - tie에서 진 쪽은 더 올릴 여지가 없다).
            ProxyEntrantStatus status = isWinner || f.cap > finalPrice
                    ? ProxyEntrantStatus.ACTIVE
                    : ProxyEntrantStatus.CAP_REACHED;
            results.add(new CandidateResult(f.userId, status));
        }

        return new ProxyResolution(finalPrice, finalWinner, priceChanged, resultingAutoBid, proxyResponded, results);
    }

    private List<Field> buildField(ProxyResolutionInput input) {
        List<Field> field = new ArrayList<>();
        for (ProxyCandidate candidate : input.candidates()) {
            long cap = EffectiveCapCalculator.calculate(candidate.maxAmount(), input.currentPrice(), input.bidIncrement());
            field.add(new Field(candidate.userId(), cap, candidate.registeredAt(), candidate.id(), false));
        }

        ProxyTrigger trigger = input.trigger();
        if (trigger instanceof ProxyTrigger.Manual manual) {
            field.add(new Field(manual.bidderUserId(), manual.amount(), LocalDateTime.MAX, null, true));
        } else if (trigger instanceof ProxyTrigger.Auto auto) {
            Long currentWinnerUserId = auto.currentWinnerUserId();
            if (currentWinnerUserId != null && field.stream().noneMatch(f -> f.userId.equals(currentWinnerUserId))) {
                field.add(new Field(currentWinnerUserId, input.currentPrice(), LocalDateTime.MAX, null, true));
            }
        } else if (trigger instanceof ProxyTrigger.None && !field.isEmpty()) {
            field.add(new Field(null, input.currentPrice(), LocalDateTime.MAX, null, true));
        }
        return field;
    }

    private Long defaultWinner(ProxyTrigger trigger) {
        if (trigger instanceof ProxyTrigger.Manual manual) {
            return manual.bidderUserId();
        }
        if (trigger instanceof ProxyTrigger.Auto auto) {
            return auto.currentWinnerUserId();
        }
        return null;
    }

    private List<CandidateResult> activeResultsOnly(List<Field> realCandidates) {
        List<CandidateResult> results = new ArrayList<>();
        for (Field f : realCandidates) {
            results.add(new CandidateResult(f.userId, ProxyEntrantStatus.ACTIVE));
        }
        return results;
    }

    private record Field(Long userId, long cap, LocalDateTime tieBreakAt, Long tieBreakId, boolean phantom) {
    }
}
