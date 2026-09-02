package com.vintic.backend.auth;

import com.vintic.backend.auth.kakao.KakaoUserInfo;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// #75-4C: identity는 kakaoUserId뿐이다 - email/nickname으로 기존 User를 찾거나 연결하지 않는다
// (account-linking 정책은 이번 범위 밖).
//
// AuctionLikeCommandService(#55)와 동일한 패턴: 동시 최초 로그인 race는 uk_users_kakao_user_id
// UNIQUE 제약이 최종 방어선이다. saveAndFlush()가 그 제약을 위반하면 예외를 그대로 던져 이
// 트랜잭션만 롤백시킨다(같은 트랜잭션에서 재시도하지 않는다 - #55가 실측한 대로 flush 실패 후
// 같은 영속성 컨텍스트를 계속 쓰면 안전하지 않다). 진 쪽은 호출자(KakaoLoginService)가
// getByKakaoUserId()로 완전히 새 트랜잭션에서 이긴 쪽이 커밋한 User를 재조회한다.
@Service
@Profile({"dev", "prod"})
public class KakaoUserFindOrCreateService {

    private final UserRepository userRepository;

    public KakaoUserFindOrCreateService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User findOrCreate(KakaoUserInfo kakaoUserInfo) {
        return userRepository.findByKakaoUserId(kakaoUserInfo.kakaoUserId())
                .orElseGet(() -> userRepository.saveAndFlush(User.registerFromKakao(
                        kakaoUserInfo.kakaoUserId(),
                        kakaoUserInfo.email(),
                        kakaoUserInfo.nickname(),
                        kakaoUserInfo.profileImageUrl()
                )));
    }

    @Transactional(readOnly = true)
    public User getByKakaoUserId(Long kakaoUserId) {
        return userRepository.findByKakaoUserId(kakaoUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "UNIQUE 충돌 이후에도 Kakao User를 찾지 못했습니다. kakaoUserId: " + kakaoUserId
                ));
    }
}
