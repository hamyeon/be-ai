package com.vintic.backend.user.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    void registerFromKakao로_생성하면_kakaoUserId가_설정된다() {
        User user = User.registerFromKakao(123L, "kakao@example.com", "카카오유저", "https://example.com/p.jpg");

        assertThat(user.getKakaoUserId()).isEqualTo(123L);
        assertThat(user.getEmail()).isEqualTo("kakao@example.com");
        assertThat(user.getNickname()).isEqualTo("카카오유저");
    }

    // #75-4C: Kakao 계정 중 일부는 이메일 동의를 하지 않았거나 계정 자체에 이메일이 없을 수
    // 있다 - email이 null이어도 가입이 가능해야 한다(identity는 kakaoUserId뿐).
    @Test
    void email이_없어도_registerFromKakao가_성공한다() {
        User user = User.registerFromKakao(456L, null, "이메일없음", null);

        assertThat(user.getKakaoUserId()).isEqualTo(456L);
        assertThat(user.getEmail()).isNull();
    }

    // 실제 장애 재현: Kakao가 프로필 동의 항목을 응답에 포함하지 않으면 nickname이 null로
    // 들어온다. nickname은 DB에서 NOT NULL이라 그대로 두면 insert가 실패했다(MySQL 1048) -
    // registerFromKakao가 기본 닉네임으로 채워야 한다.
    @Test
    void nickname이_null이면_기본_닉네임이_채워진다() {
        User user = User.registerFromKakao(789L, "a@example.com", null, null);

        assertThat(user.getNickname()).isNotNull().startsWith("회원-");
    }

    @Test
    void nickname이_빈_문자열이어도_기본_닉네임이_채워진다() {
        User user = User.registerFromKakao(790L, "a@example.com", "   ", null);

        assertThat(user.getNickname()).isNotNull().startsWith("회원-");
    }

    @Test
    void kakaoUserId가_없으면_registerFromKakao가_실패한다() {
        assertThatThrownBy(() -> User.registerFromKakao(null, "a@example.com", "nick", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 기존_register는_kakaoUserId가_null이다() {
        User user = User.register("mock@example.com", "mock", null);

        assertThat(user.getKakaoUserId()).isNull();
    }
}
