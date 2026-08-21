package com.vintic.backend.recommendation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserActivityLogTest {

    @Test
    void 경매와_상품_id를_모두_남긴다() {
        // 추천 대상은 경매지만 취향은 상품 특성에서 나온다. 둘 다 있어야 어느 쪽으로도 집계할 수 있다.
        UserActivityLog log = UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW);

        assertThat(log.getAuctionId()).isEqualTo(10L);
        assertThat(log.getProductId()).isEqualTo(100L);
        assertThat(log.getActivityType()).isEqualTo(ActivityType.VIEW);
        assertThat(log.getCreatedAt()).isNotNull();
    }

    @Test
    void 경매나_상품_중_하나만_있어도_기록된다() {
        // 입찰은 auctionId만, 상품 조회는 productId만 아는 경우가 있다
        assertThat(UserActivityLog.record(1L, 10L, null, ActivityType.BID).getProductId()).isNull();
        assertThat(UserActivityLog.record(1L, null, 100L, ActivityType.VIEW).getAuctionId()).isNull();
    }

    @Test
    void 대상이_아무것도_없으면_거부한다() {
        // 무엇을 봤는지 모르는 로그는 추천에 쓸 수 없다
        assertThatThrownBy(() -> UserActivityLog.record(1L, null, null, ActivityType.VIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("경매 또는 상품");
    }

    @Test
    void 사용자와_행동_유형은_필수다() {
        assertThatThrownBy(() -> UserActivityLog.record(null, 10L, 100L, ActivityType.VIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserActivityLog.record(1L, 10L, 100L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 체류_시간은_없어도_되고_음수는_거부한다() {
        // 프론트 연동 전까지 DWELL 로그는 쌓이지 않으므로 null이 정상이다
        assertThat(UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW).getDwellSeconds()).isNull();
        assertThat(UserActivityLog.record(1L, 10L, 100L, ActivityType.DWELL, 12).getDwellSeconds()).isEqualTo(12);

        assertThatThrownBy(() -> UserActivityLog.record(1L, 10L, 100L, ActivityType.DWELL, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
