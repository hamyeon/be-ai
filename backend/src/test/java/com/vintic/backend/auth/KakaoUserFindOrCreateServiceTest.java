package com.vintic.backend.auth;

import com.vintic.backend.auth.kakao.KakaoUserInfo;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// #75-4C: identity는 kakaoUserId뿐이다. @Profile({"dev","prod"})로 제한된 빈이라 dev profile을
// 활성화한다 - @DataJpaTest는 기본적으로 내장 H2로 대체하므로 실제 dev RDS와는 무관하다.
@DataJpaTest
@ActiveProfiles("dev")
@Import(KakaoUserFindOrCreateService.class)
class KakaoUserFindOrCreateServiceTest {

    @Autowired
    private KakaoUserFindOrCreateService kakaoUserFindOrCreateService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void 최초_로그인이면_User가_1건_생성된다() {
        KakaoUserInfo info = new KakaoUserInfo(100L, "first@example.com", "첫유저", "https://example.com/p.jpg");

        User created = kakaoUserFindOrCreateService.findOrCreate(info);
        flushAndClear();

        assertThat(created.getId()).isNotNull();
        assertThat(created.getKakaoUserId()).isEqualTo(100L);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 동일_kakaoUserId로_재로그인하면_같은_User를_반환하고_중복_생성하지_않는다() {
        KakaoUserInfo info = new KakaoUserInfo(200L, "user@example.com", "유저", null);
        User first = kakaoUserFindOrCreateService.findOrCreate(info);
        flushAndClear();

        User second = kakaoUserFindOrCreateService.findOrCreate(info);
        flushAndClear();

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    // #75-4C 필수 invariant: email 변경 -> 신규 User 생성 안 됨. identity는 kakaoUserId뿐이라
    // 재로그인 시 email이 달라져도 새 User를 만들지 않는다(기존 User를 그대로 반환).
    @Test
    void email이_바뀌어도_동일_kakaoUserId면_같은_User를_반환한다() {
        KakaoUserInfo firstLogin = new KakaoUserInfo(300L, "old@example.com", "유저", null);
        User first = kakaoUserFindOrCreateService.findOrCreate(firstLogin);
        flushAndClear();

        KakaoUserInfo secondLoginWithNewEmail = new KakaoUserInfo(300L, "new@example.com", "유저", null);
        User second = kakaoUserFindOrCreateService.findOrCreate(secondLoginWithNewEmail);
        flushAndClear();

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void email이_없는_Kakao_사용자도_가입할_수_있다() {
        KakaoUserInfo info = new KakaoUserInfo(400L, null, "이메일없음", null);

        User created = kakaoUserFindOrCreateService.findOrCreate(info);
        flushAndClear();

        assertThat(created.getEmail()).isNull();
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void 서로_다른_kakaoUserId는_서로_다른_User로_생성된다() {
        kakaoUserFindOrCreateService.findOrCreate(new KakaoUserInfo(501L, "a@example.com", "A", null));
        kakaoUserFindOrCreateService.findOrCreate(new KakaoUserInfo(502L, "b@example.com", "B", null));
        flushAndClear();

        assertThat(userRepository.count()).isEqualTo(2);
    }
}
