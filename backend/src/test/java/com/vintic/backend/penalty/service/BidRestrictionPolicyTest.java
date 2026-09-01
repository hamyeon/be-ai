package com.vintic.backend.penalty.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BidRestrictionPolicyTest {

    @Test
    void 설정된_일수만큼_now에_더한_시각을_반환한다() {
        BidRestrictionPolicy policy = new BidRestrictionPolicy(7);
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 22, 0, 0);

        assertThat(policy.restrictedUntil(now)).isEqualTo(now.plusDays(7));
    }

    @Test
    void 설정값이_다르면_다른_기간을_반환한다() {
        BidRestrictionPolicy policy = new BidRestrictionPolicy(3);
        LocalDateTime now = LocalDateTime.of(2026, 8, 18, 22, 0, 0);

        assertThat(policy.restrictedUntil(now)).isEqualTo(now.plusDays(3));
    }
}
