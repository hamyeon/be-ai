package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.autobid.repository.AutoBidSettingRepository;
import com.vintic.backend.common.exception.PurchaseGoalAccessDeniedException;
import com.vintic.backend.common.exception.PurchaseGoalNotFoundException;
import com.vintic.backend.order.repository.OrderRepository;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalDetailResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalMatchHistoryResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalParticipationResponse;
import com.vintic.backend.purchasegoal.dto.PurchaseGoalResponse;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalMatchRepository;
import com.vintic.backend.purchasegoal.repository.PurchaseGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// OrderQueryService.getOrder()와 동일한 관례를 쓴다 - 소유자 필터 없이 id로 먼저 조회해 404와
// 403(소유자 아님)을 구분한다.
//
// Day 7/8: 참여 이력(participationCount/wonCount, 상세의 participations)은 별도 이력 테이블이나
// Goal의 카운트 필드 없이 매 조회 시점에 purchaseGoalId가 연결된 AutoBidSetting +
// Auction.status + Order 존재 여부로 계산한다. goal 개수·경매 개수와 무관하게 쿼리 3번(AutoBidSetting,
// Auction, Order)으로 고정해 N+1을 피한다.
//
// wonCount는 "goal에 연결된 경매들 중 본인 Order가 있는 경매 수"다 - Order.status(결제 완료 여부)는
// 보지 않는다(낙찰=Order 존재, 결제 완료와는 다른 개념). 한 auction당 Order는 (auction, buyer)
// UNIQUE 제약으로 최대 1건이므로 이 값은 실제 Order 건수와 정확히 같다. 상한을 1로 두지 않는다 -
// 한 Goal이 순차적으로 여러 경매에 참여하며 정산이든 BackupOffer 수락이든 두 번 이상 낙찰할 수
// 있고, 그 경우 그대로 2 이상을 반환해야 한다.
@Service
public class PurchaseGoalQueryService {

    private final PurchaseGoalRepository purchaseGoalRepository;
    private final AutoBidSettingRepository autoBidSettingRepository;
    private final AuctionRepository auctionRepository;
    private final OrderRepository orderRepository;
    private final PurchaseGoalMatchRepository purchaseGoalMatchRepository;

    public PurchaseGoalQueryService(
            PurchaseGoalRepository purchaseGoalRepository,
            AutoBidSettingRepository autoBidSettingRepository,
            AuctionRepository auctionRepository,
            OrderRepository orderRepository,
            PurchaseGoalMatchRepository purchaseGoalMatchRepository
    ) {
        this.purchaseGoalRepository = purchaseGoalRepository;
        this.autoBidSettingRepository = autoBidSettingRepository;
        this.auctionRepository = auctionRepository;
        this.orderRepository = orderRepository;
        this.purchaseGoalMatchRepository = purchaseGoalMatchRepository;
    }

    // 페이지네이션이 없는 완전한 목록이다 - 전체 참여/낙찰 합계가 필요하면 프론트가 이 목록을
    // 그대로 합산하면 된다(#Day7, 별도 합계 endpoint를 추가하지 않는다).
    @Transactional(readOnly = true)
    public List<PurchaseGoalResponse> getMyGoals(Long userId) {
        List<PurchaseGoal> goals = purchaseGoalRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (goals.isEmpty()) {
            return List.of();
        }

        List<Long> goalIds = goals.stream().map(PurchaseGoal::getId).toList();
        Map<Long, List<PurchaseGoalParticipationResponse>> participationsByGoal = participationsByGoalId(goalIds, userId);

        return goals.stream()
                .map(goal -> {
                    List<PurchaseGoalParticipationResponse> participations =
                            participationsByGoal.getOrDefault(goal.getId(), List.of());
                    int wonCount = (int) participations.stream().filter(p -> "WON".equals(p.status())).count();
                    return PurchaseGoalResponse.from(goal, participations.size(), wonCount);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseGoalDetailResponse getGoal(Long goalId, Long userId) {
        PurchaseGoal goal = purchaseGoalRepository.findById(goalId)
                .orElseThrow(() -> new PurchaseGoalNotFoundException("존재하지 않는 구매 목표입니다. goalId: " + goalId));

        if (!goal.getUser().getId().equals(userId)) {
            throw new PurchaseGoalAccessDeniedException("접근 권한이 없는 구매 목표입니다. goalId: " + goalId);
        }

        List<PurchaseGoalParticipationResponse> participations =
                participationsByGoalId(List.of(goalId), userId).getOrDefault(goalId, List.of());
        return PurchaseGoalDetailResponse.from(goal, participations);
    }

    // Day 8: GET /api/purchase-goals/{id}/matches 전용. 참여 이력(AutoBidSetting 기반)과 완전히
    // 분리된 별도 조회다 - Matcher가 평가만 하고 등록까지 가지 않은 경매(matched=false 포함)도
    // 여기서는 보이지만 참여 이력에는 없다. getGoal()과 동일한 404 -> 403 순서를 그대로 쓴다.
    @Transactional(readOnly = true)
    public List<PurchaseGoalMatchHistoryResponse> getMatchHistory(Long goalId, Long userId) {
        PurchaseGoal goal = purchaseGoalRepository.findById(goalId)
                .orElseThrow(() -> new PurchaseGoalNotFoundException("존재하지 않는 구매 목표입니다. goalId: " + goalId));

        if (!goal.getUser().getId().equals(userId)) {
            throw new PurchaseGoalAccessDeniedException("접근 권한이 없는 구매 목표입니다. goalId: " + goalId);
        }

        return purchaseGoalMatchRepository.findByGoalIdOrderByEvaluatedAtDesc(goalId).stream()
                .map(PurchaseGoalMatchHistoryResponse::from)
                .toList();
    }

    // 1) purchaseGoalId가 연결된 AutoBidSetting에서 (goal, auction) 쌍을 모으고 - 경매별로 한 번만
    //    세도록 goal당 auctionId를 Set으로 다시 묶는다(설계상 (goal,auction)은 최대 1건이지만
    //    한 번 더 보장한다). 2) 등장한 경매들의 현재 상태를 한 번에 읽고, 3) 그중 ENDED인 경매만
    //    호출자(userId) 본인의 Order 존재 여부를 한 번에 묻는다 - userId는 이미 소유자 검증을
    //    마친 호출자 자신이므로 다른 사용자의 Order가 섞일 수 없다.
    private Map<Long, List<PurchaseGoalParticipationResponse>> participationsByGoalId(List<Long> goalIds, Long userId) {
        List<AutoBidSettingRepository.PurchaseGoalAuctionPair> pairs =
                autoBidSettingRepository.findAuctionPairsByPurchaseGoalIdIn(goalIds);
        if (pairs.isEmpty()) {
            return Map.of();
        }

        Map<Long, Set<Long>> auctionIdsByGoal = new LinkedHashMap<>();
        for (AutoBidSettingRepository.PurchaseGoalAuctionPair pair : pairs) {
            auctionIdsByGoal.computeIfAbsent(pair.getGoalId(), k -> new LinkedHashSet<>()).add(pair.getAuctionId());
        }

        Set<Long> allAuctionIds = pairs.stream()
                .map(AutoBidSettingRepository.PurchaseGoalAuctionPair::getAuctionId)
                .collect(Collectors.toSet());
        Map<Long, AuctionStatus> statusByAuctionId = new HashMap<>();
        for (Auction auction : auctionRepository.findAllById(allAuctionIds)) {
            statusByAuctionId.put(auction.getId(), auction.getStatus());
        }

        List<Long> endedAuctionIds = statusByAuctionId.entrySet().stream()
                .filter(entry -> entry.getValue() == AuctionStatus.ENDED)
                .map(Map.Entry::getKey)
                .toList();
        Set<Long> wonAuctionIds = endedAuctionIds.isEmpty()
                ? Set.of()
                : Set.copyOf(orderRepository.findWonAuctionIds(endedAuctionIds, userId));

        Map<Long, List<PurchaseGoalParticipationResponse>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : auctionIdsByGoal.entrySet()) {
            List<PurchaseGoalParticipationResponse> participations = entry.getValue().stream()
                    .map(auctionId -> new PurchaseGoalParticipationResponse(
                            auctionId,
                            classify(statusByAuctionId.get(auctionId), wonAuctionIds.contains(auctionId))
                    ))
                    .toList();
            result.put(entry.getKey(), participations);
        }
        return result;
    }

    // RESERVED(AutoBidSetting)는 항상 Auction.SCHEDULED와 함께 다닌다(activate()가 경매 시작
    // 시점에만 호출된다) - AutoBidSetting.status를 추측하지 않고 권위 있는 Auction.status를
    // 그대로 쓴다. 종료된 경매의 낙찰 여부도 Goal/AutoBid 상태를 추측하지 않고 Order로만 판정한다.
    private String classify(AuctionStatus auctionStatus, boolean won) {
        return switch (auctionStatus) {
            case SCHEDULED -> "SCHEDULED";
            case LIVE -> "LIVE";
            case ENDED -> won ? "WON" : "LOST";
            case CANCELED -> "LOST";
        };
    }
}
