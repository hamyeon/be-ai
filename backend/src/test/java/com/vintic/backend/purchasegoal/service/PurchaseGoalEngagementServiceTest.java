package com.vintic.backend.purchasegoal.service;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.match.MatchResult;
import com.vintic.backend.ai.purchase.price.PriceEstimate;
import com.vintic.backend.ai.purchase.price.PriceEstimateProvider;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.purchasegoal.domain.PurchaseGoal;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// PurchaseGoalEngagementService는 트랜잭션도 DB도 갖지 않는 순수 오케스트레이션이라(트랜잭션
// 경계는 PurchaseGoalEngagementTransactionService 하나뿐) Mockito만으로 단위 테스트한다 -
// AutoBidCommandServiceTest류의 @DataJpaTest 관례를 따르지 않는 유일한 Day 5 테스트다.
class PurchaseGoalEngagementServiceTest {

    private final PriceEstimateProvider priceEstimateProvider = mock(PriceEstimateProvider.class);
    private final PurchaseGoalEngagementTransactionService transactionService = mock(PurchaseGoalEngagementTransactionService.class);
    private final PurchaseGoalEngagementService engagementService =
            new PurchaseGoalEngagementService(priceEstimateProvider, transactionService);

    private PurchaseGoal goal(Long id, long hardMaxAmount) {
        User user = User.register("buyer@vintic.local", "buyer", null);
        ReflectionTestUtils.setField(user, "id", 10L);
        LocalDateTime now = LocalDateTime.now();
        PurchaseGoal g = PurchaseGoal.create(
                user, "New Balance", "nb990", "뉴발란스 990",
                GoalCondition.B, null, hardMaxAmount, null, now.plusDays(1), now
        );
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    private Auction auction(Long id) {
        User seller = User.register("seller@vintic.local", "seller", null);
        Product product = new Product(
                seller, List.of(), "New Balance", "990v6", "Grey", 270, "A", "PARTIAL",
                300000, 350000, "", 290000, "", ""
        );
        Auction auction = Auction.schedule(product, 100000L, 5000L, LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        auction.start();
        ReflectionTestUtils.setField(auction, "id", id);
        return auction;
    }

    private PurchaseGoalRankedCandidate candidate(Auction auction) {
        PriceEstimate snapshotEstimate = new PriceEstimate(999_999_999, 1, 1, PriceEstimate.Source.USED_MARKET, 10, "day4 스냅샷", LocalDateTime.now());
        MatchResult matchResult = new MatchResult(true, 0.7, "일치", "nb990");
        return new PurchaseGoalRankedCandidate(auction, snapshotEstimate, matchResult, 999_999_999L);
    }

    private PriceEstimate freshEstimate(int estimatedPrice) {
        return new PriceEstimate(estimatedPrice, estimatedPrice - 10000, estimatedPrice + 10000,
                PriceEstimate.Source.USED_MARKET, 10, "재확인", LocalDateTime.now());
    }

    @Test
    void 후보가_없으면_아무것도_하지_않는다() {
        PurchaseGoalEngagementResult result = engagementService.attemptEngage(goal(1L, 500000L), Optional.empty());

        assertThat(result.engaged()).isFalse();
        verifyNoInteractions(priceEstimateProvider, transactionService);
    }

    @Test
    void 서버_시세가_없으면_참여하지_않고_트랜잭션_서비스를_호출하지_않는다() {
        Auction auction = auction(1L);
        when(priceEstimateProvider.estimate(any())).thenReturn(Optional.empty());

        PurchaseGoalEngagementResult result = engagementService.attemptEngage(goal(1L, 500000L), Optional.of(candidate(auction)));

        assertThat(result.engaged()).isFalse();
        verifyNoInteractions(transactionService);
    }

    @Test
    void 시세_재확인이_예외를_던져도_참여하지_않는다() {
        Auction auction = auction(1L);
        when(priceEstimateProvider.estimate(any())).thenThrow(new RuntimeException("가격 조회 실패"));

        PurchaseGoalEngagementResult result = engagementService.attemptEngage(goal(1L, 500000L), Optional.of(candidate(auction)));

        assertThat(result.engaged()).isFalse();
        verifyNoInteractions(transactionService);
    }

    // Day 4의 cap 스냅샷(999_999_999)이 아니라 방금 다시 받은 서버 시세를 근거로 cap을 다시
    // 계산해 트랜잭션 서비스에 넘기는지 확인한다.
    @Test
    void cap은_hardMaxAmount와_새로_받은_시세_중_작은_값으로_재계산한다() {
        Auction auction = auction(1L);
        PurchaseGoal goal = goal(1L, 150000L); // hardMaxAmount < 새 시세
        when(priceEstimateProvider.estimate(any())).thenReturn(Optional.of(freshEstimate(300000)));
        when(transactionService.engage(eq(1L), eq(1L), eq(10L), eq(150000L)))
                .thenReturn(PurchaseGoalEngagementResult.engaged(1L, 99L));

        PurchaseGoalEngagementResult result = engagementService.attemptEngage(goal, Optional.of(candidate(auction)));

        assertThat(result.engaged()).isTrue();
        assertThat(result.autoBidSettingId()).isEqualTo(99L);
        verify(transactionService).engage(1L, 1L, 10L, 150000L);
    }

    @Test
    void 트랜잭션_서비스가_예외를_던지면_잡아서_참여하지_않음으로_바꾼다() {
        Auction auction = auction(1L);
        when(priceEstimateProvider.estimate(any())).thenReturn(Optional.of(freshEstimate(300000)));
        when(transactionService.engage(any(), any(), any(), anyLong()))
                .thenThrow(new AuctionClosedException("이미 종료되었거나 취소된 경매입니다."));

        PurchaseGoalEngagementResult result = engagementService.attemptEngage(goal(1L, 500000L), Optional.of(candidate(auction)));

        assertThat(result.engaged()).isFalse();
        assertThat(result.reason()).contains("종료");
    }
}
