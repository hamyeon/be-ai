package com.vintic.backend.user.domain;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

// bidRestrictedUntil은 페널티 생성 정책(이번 범위 제외)에서만 채워질 값이라
// User에 별도 setter/factory 파라미터를 추가하지 않고, 테스트에서만 리플렉션으로 채운다.
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
}
