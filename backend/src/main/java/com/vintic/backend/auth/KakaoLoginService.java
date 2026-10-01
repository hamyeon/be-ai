package com.vintic.backend.auth;

import com.vintic.backend.auth.dto.KakaoLoginResponse;
import com.vintic.backend.auth.jwt.JwtClaims;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.kakao.KakaoUserInfo;
import com.vintic.backend.auth.kakao.KakaoUserInfoClient;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.user.domain.User;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

// #75-4C: POST /api/auth/kakao의 orchestrator(Controller의 얇은 진입점, ManualBidService/
// AuctionLikeService와 동일한 역할 분담). @Transactional을 직접 갖지 않는다 - 실제 트랜잭션
// 경계는 KakaoUserFindOrCreateService가 갖는다.
//
// Kakao access token은 여기서만 쓰인다 - 검증 이후에는 폐기하고 다시 저장/전달하지 않는다.
// 이후 API 인증은 이 메서드가 발급하는 자체 Access JWT만 사용한다.
//
// #75-4D: Refresh Token은 Redis 저장까지 성공해야 로그인이 성공한다 - RefreshTokenStore.save()가
// 던지는 예외(Redis 장애 등)를 잡지 않고 그대로 전파한다(GlobalExceptionHandler의 기존 catch-all
// 5xx 처리로 수렴). User 생성(DB)과 Redis 저장은 하나의 트랜잭션이 아니다 - 신규 User가 이미
// 커밋된 뒤 Redis 저장이 실패하면 User row만 남고 로그인은 실패 응답을 받는 상태가 구조적으로
// 가능하다(이 User는 다음 재로그인 시도에서 findByKakaoUserId로 그대로 재사용되므로 다시 로그인을
// 시도하면 정상 복구된다 - 이 gap을 해소하기 위해 distributed transaction/outbox는 도입하지 않는다).
@Service
@Profile({"dev", "prod"})
public class KakaoLoginService {

    private final KakaoUserInfoClient kakaoUserInfoClient;
    private final KakaoUserFindOrCreateService kakaoUserFindOrCreateService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    public KakaoLoginService(
            KakaoUserInfoClient kakaoUserInfoClient,
            KakaoUserFindOrCreateService kakaoUserFindOrCreateService,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenStore refreshTokenStore
    ) {
        this.kakaoUserInfoClient = kakaoUserInfoClient;
        this.kakaoUserFindOrCreateService = kakaoUserFindOrCreateService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenStore = refreshTokenStore;
    }

    public KakaoLoginResponse login(String kakaoAccessToken) {
        KakaoUserInfo kakaoUserInfo = kakaoUserInfoClient.getUserInfo(kakaoAccessToken);
        User user = resolveUser(kakaoUserInfo);

        String accessToken = jwtTokenProvider.issueAccessToken(user.getId());
        String refreshToken = jwtTokenProvider.issueRefreshToken(user.getId());
        // 발급 직후 그대로 다시 파싱해 만료시각을 얻는다 - TTL 계산을 이 클래스에서 중복하지
        // 않는다(JwtProperties는 JwtTokenProvider만 참조한다는 #75-4A 원칙 유지). Redis TTL도
        // 이 실제 expiresAt을 그대로 쓴다(RefreshTokenStore 참고).
        JwtClaims accessClaims = jwtTokenProvider.parseAccessToken(accessToken);
        JwtClaims refreshClaims = jwtTokenProvider.parseRefreshToken(refreshToken);

        // Redis 저장이 실패하면(장애 등) 예외가 그대로 위로 전파되어 이 메서드는 응답을
        // 반환하지 않는다 - refresh token을 반환해놓고 서버가 기억하지 못하는 상태를 만들지 않는다.
        refreshTokenStore.save(refreshClaims.jti(), user.getId(), refreshClaims.expiresAt());

        return new KakaoLoginResponse(
                accessToken,
                refreshToken,
                OffsetDateTime.ofInstant(accessClaims.expiresAt(), ClockConfig.APP_ZONE),
                OffsetDateTime.ofInstant(refreshClaims.expiresAt(), ClockConfig.APP_ZONE)
        );
    }

    private User resolveUser(KakaoUserInfo kakaoUserInfo) {
        try {
            return kakaoUserFindOrCreateService.findOrCreate(kakaoUserInfo);
        } catch (DataIntegrityViolationException e) {
            // 동시 최초 로그인 race - uk_users_kakao_user_id가 최종 방어선이다. 진 쪽은 이긴
            // 쪽이 커밋한 User를 완전히 새 트랜잭션에서 재조회한다(AuctionLikeService와 동일 패턴).
            return kakaoUserFindOrCreateService.getByKakaoUserId(kakaoUserInfo.kakaoUserId());
        }
    }
}
