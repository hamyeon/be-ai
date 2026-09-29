package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.match.AuctionListing;
import com.vintic.backend.ai.purchase.match.ListingMatcher;
import com.vintic.backend.ai.purchase.match.MatchGoal;
import com.vintic.backend.ai.purchase.match.MatchResult;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.domain.PurchaseGoalMatch;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalMatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// Purchase Agent Day 4: Day 3 후보를 Matcher로 평가(캐시 재사용 포함)하고, matched=true만 cap을
// 계산해 4단계 기준으로 정렬한 뒤 최상위 후보 하나를 반환한다. Scheduler 실행·Goal 상태 전이·
// AutoBid 생성·정산은 이 서비스의 책임이 아니다(Day 5) - 여기서는 "지금 시점에 가장 참여할
// 만한 후보가 무엇인가"만 결정한다.
@Service
@Slf4j
public class PurchaseGoalCandidateRanker {

    // 설계안 5-2 초기값. cap = min(hardMaxAmount, 서버 시세 * CAP_RATIO).
    private static final double CAP_RATIO = 1.0;

    private static final Comparator<PurchaseGoalRankedCandidate> RANKING_ORDER =
            Comparator.<PurchaseGoalRankedCandidate>comparingDouble(PurchaseGoalCandidateRanker::discountRate).reversed()
                    .thenComparing(candidate -> candidate.auction().getEndAt())
                    .thenComparing(Comparator.<PurchaseGoalRankedCandidate>comparingDouble(
                            candidate -> candidate.matchResult().semanticScore()).reversed())
                    .thenComparing(candidate -> candidate.auction().getId());

    private final ListingMatcher listingMatcher;
    private final PurchaseGoalMatchRepository purchaseGoalMatchRepository;
    private final Clock clock;

    public PurchaseGoalCandidateRanker(
            ListingMatcher listingMatcher,
            PurchaseGoalMatchRepository purchaseGoalMatchRepository,
            Clock clock
    ) {
        this.listingMatcher = listingMatcher;
        this.purchaseGoalMatchRepository = purchaseGoalMatchRepository;
        this.clock = clock;
    }

    // 이 메서드에는 의도적으로 @Transactional을 붙이지 않는다 - listingMatcher.evaluate()(AI 호출,
    // 지연될 수 있음)를 기다리는 동안 DB 락이나 트랜잭션을 열어 두지 않기 위해서다. 캐시 조회/저장은
    // 각 repository 메서드 호출 단위로 개별적인 짧은 트랜잭션이면 충분하다(Spring Data JPA 기본값).
    public Optional<PurchaseGoalRankedCandidate> rankTopCandidate(PurchaseGoal goal, List<PurchaseGoalCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<Long> auctionIds = candidates.stream().map(candidate -> candidate.auction().getId()).toList();
        Map<Long, PurchaseGoalMatch> cachedByAuctionId = purchaseGoalMatchRepository
                .findByGoalIdAndAuctionIdIn(goal.getId(), auctionIds)
                .stream()
                .collect(Collectors.toMap(PurchaseGoalMatch::getAuctionId, match -> match));

        MatchGoal matchGoal = new MatchGoal(goal.getModelKey(), goal.getModelQuery(), goal.getBrand(), goal.getFreeTextConditions());

        List<PurchaseGoalRankedCandidate> ranked = new ArrayList<>();
        for (PurchaseGoalCandidate candidate : candidates) {
            Auction auction = candidate.auction();
            MatchResult matchResult = resolveMatchResult(goal, matchGoal, auction, cachedByAuctionId.get(auction.getId()));
            if (matchResult == null || !matchResult.matched()) {
                continue;
            }

            long cap = computeCap(goal, candidate.priceEstimate());
            if (cap < auction.getMinNextBidAmount()) {
                continue;
            }

            ranked.add(new PurchaseGoalRankedCandidate(auction, candidate.priceEstimate(), matchResult, cap));
        }

        return ranked.stream().min(RANKING_ORDER);
    }

    // 캐시 hit면 matched=false 이력도 그대로 재사용한다(Matcher 재호출 없음). 캐시 miss면 Matcher를
    // 호출하고, 성공하면 결과를 즉시 저장한다(matched=false도 저장). 실패(예외)면 null을 돌려줘
    // 이번 평가에서 그 후보만 빠지고 이력은 남기지 않는다 - 다음 scan에서 다시 시도된다.
    private MatchResult resolveMatchResult(PurchaseGoal goal, MatchGoal matchGoal, Auction auction, PurchaseGoalMatch cached) {
        if (cached != null) {
            return new MatchResult(cached.isMatched(), cached.getSemanticScore(), cached.getReason(), cached.getListingModelKey());
        }

        MatchResult result;
        try {
            result = listingMatcher.evaluate(matchGoal, toListing(auction));
        } catch (RuntimeException e) {
            log.warn("Purchase Agent Matcher 평가 실패 - goalId: {}, auctionId: {}", goal.getId(), auction.getId(), e);
            return null;
        }

        PurchaseGoalMatch match = PurchaseGoalMatch.create(
                goal.getId(), auction.getId(), result.matched(), result.semanticScore(),
                result.reason(), result.listingModelKey(), LocalDateTime.now(clock)
        );
        purchaseGoalMatchRepository.save(match);
        return result;
    }

    // 등급·사이즈·예산은 Day 3 pre-filter가 이미 끝냈으므로 여기서는 넘기지 않는다(ListingMatcher
    // 계약 그대로 - modelKey/modelQuery/brand/freeTextConditions + brand/model/colorway/title/description).
    // Product에는 title 필드가 없어(Day 0 §4 확인) null로 둔다 - description만 넘긴다.
    private AuctionListing toListing(Auction auction) {
        Product product = auction.getProduct();
        return new AuctionListing(auction.getId(), product.getBrand(), product.getModel(), product.getColorway(), null, product.getDescription());
    }

    // 기존 AutoBid 금액 규칙 중 이번에 실제로 필요한 것만 적용한다: cap이 auction.getMinNextBidAmount()
    // (AutoBidCommandService.createAutoBid의 CapTooLowException 기준과 동일한 식)보다 낮으면 제외.
    // bidIncrement 그리드 정렬(EffectiveCapCalculator)과 update 전용 규칙(CapNotIncreasedException)은
    // 여기서 만드는 cap이 신규 후보 평가값일 뿐 실제 등록/수정이 아니라 적용 대상이 아니다.
    // Product.recommendedPrice(판매자 입력값)는 쓰지 않는다 - PriceEstimateProvider의 서버 시세만 근거로 삼는다.
    private long computeCap(PurchaseGoal goal, PriceEstimate priceEstimate) {
        long capByEstimate = (long) Math.floor(priceEstimate.estimatedPrice() * CAP_RATIO);
        return Math.min(goal.getHardMaxAmount(), capByEstimate);
    }

    // (서버 시세 - 현재가) / 서버 시세. Day 3가 estimatedPrice>0인 시세만 후보에 남기므로 0으로
    // 나눌 일은 없다.
    private static double discountRate(PurchaseGoalRankedCandidate candidate) {
        double estimatedPrice = candidate.priceEstimate().estimatedPrice();
        double currentPrice = candidate.auction().getCurrentPrice();
        return (estimatedPrice - currentPrice) / estimatedPrice;
    }
}
