package com.vintic.backend.user.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// isBidRestricted() 자체를 검증하는 케이스는 recordPaymentExpiredPenalty()(#57-2) 없이도
// 임의의 시각을 리플렉션으로 채워 확인한다 - recordPaymentExpiredPenalty()의 실제 동작은
// 아래 별도 케이스에서 공개 API로 검증한다.
class UserTest {

    @Test
    void bidRestrictedUntil이_null이면_제한되지_않는다() {
        User user = User.register("user@vintic.local", "user", null);

        assertThat(user.isBidRestricted(LocalDateTime.now())).isFalse();
    }

    @Test
    void bidRestrictedUntil이_현재보다_과거이면_제한되지_않는다() {
        User user = User.register("user@vintic.local", "user", null);
        ReflectionTestUtils.setField(user, "bidRestrictedUntil", LocalDateTime.now().minusMinutes(1));

        assertThat(user.isBidRestricted(LocalDateTime.now())).isFalse();
    }

    @Test
    void bidRestrictedUntil이_현재보다_미래이면_제한된다() {
        User user = User.register("user@vintic.local", "user", null);
        ReflectionTestUtils.setField(user, "bidRestrictedUntil", LocalDateTime.now().plusMinutes(1));

        assertThat(user.isBidRestricted(LocalDateTime.now())).isTrue();
    }

    @Test
    void PAYMENT_EXPIRED_페널티를_기록하면_noshowCount가_증가하고_bidRestrictedUntil이_설정된다() {
        User user = User.register("user@vintic.local", "user", null);
        LocalDateTime restrictedUntil = LocalDateTime.now().plusDays(7);

        user.recordPaymentExpiredPenalty(restrictedUntil);

        assertThat(user.getNoshowCount()).isEqualTo(1);
        assertThat(user.getBidRestrictedUntil()).isEqualTo(restrictedUntil);
    }

    @Test
    void PAYMENT_EXPIRED_페널티를_여러번_기록하면_noshowCount가_누적된다() {
        User user = User.register("user@vintic.local", "user", null);

        user.recordPaymentExpiredPenalty(LocalDateTime.now().plusDays(7));
        user.recordPaymentExpiredPenalty(LocalDateTime.now().plusDays(14));

        assertThat(user.getNoshowCount()).isEqualTo(2);
    }
}
