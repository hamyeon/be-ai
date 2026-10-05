package com.vintic.backend.auth;

import com.vintic.backend.auth.dto.KakaoLoginResponse;
import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.kakao.KakaoUserInfo;
import com.vintic.backend.auth.kakao.KakaoUserInfoClient;
import com.vintic.backend.common.exception.KakaoApiException;
import com.vintic.backend.common.exception.KakaoTokenInvalidException;
import com.vintic.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// #75-4C: 실제 Spring context/DB 없이 KakaoLoginService의 orchestration 로직만 검증한다.
// JwtTokenProvider는 실제 JJWT primitive를 그대로 써서(mock 아님) accessToken이 진짜 유효한
// JWT이고 subject가 내부 User.id인지까지 확인한다.
@ExtendWith(MockitoExtension.class)
class KakaoLoginServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-02T11:00:00Z");

    @Mock
    private KakaoUserInfoClient kakaoUserInfoClient;

    @Mock
    private KakaoUserFindOrCreateService kakaoUserFindOrCreateService;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private JwtTokenProvider jwtTokenProvider;
    private KakaoLoginService kakaoLoginService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("kakao-login-service-test-secret-32-bytes-minimum!!");
        properties.setAccessTtlSeconds(1800);
        properties.setRefreshTtlSeconds(1_209_600);
        jwtTokenProvider = new JwtTokenProvider(properties, Clock.fixed(FIXED_NOW, ZoneId.of("Asia/Seoul")));
        kakaoLoginService = new KakaoLoginService(
                kakaoUserInfoClient, kakaoUserFindOrCreateService, jwtTokenProvider, refreshTokenStore
        );
    }

    private User userWithId(Long id, Long kakaoUserId) {
        User user = User.registerFromKakao(kakaoUserId, "u@example.com", "닉네임", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    void 정상_Kakao_토큰이면_로그인에_성공하고_Access_subject가_내부_User_id다() {
        KakaoUserInfo info = new KakaoUserInfo(999L, "u@example.com", "닉네임", null);
        when(kakaoUserInfoClient.getUserInfo("valid-token")).thenReturn(info);
        when(kakaoUserFindOrCreateService.findOrCreate(info)).thenReturn(userWithId(42L, 999L));

        KakaoLoginResponse response = kakaoLoginService.login("valid-token");

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.accessToken()).isNotEqualTo(response.refreshToken());
        assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()).userId()).isEqualTo(42L);
        assertThat(jwtTokenProvider.parseRefreshToken(response.refreshToken()).userId()).isEqualTo(42L);
        assertThat(response.accessTokenExpiresAt()).isEqualTo(response.refreshTokenExpiresAt().minusSeconds(1_209_600 - 1800));

        String refreshJti = jwtTokenProvider.parseRefreshToken(response.refreshToken()).jti();
        verify(refreshTokenStore).save(refreshJti, 42L, FIXED_NOW.plusSeconds(1_209_600));
    }

    // #75-4D: Redis 저장까지 성공해야 로그인이 성공한다 - 저장 실패 시 예외가 그대로
    // 전파되고 KakaoLoginResponse는 반환되지 않는다.
    @Test
    void Redis_저장이_실패하면_로그인_응답을_반환하지_않는다() {
        KakaoUserInfo info = new KakaoUserInfo(555L, "u@example.com", "닉네임", null);
        when(kakaoUserInfoClient.getUserInfo("token")).thenReturn(info);
        when(kakaoUserFindOrCreateService.findOrCreate(info)).thenReturn(userWithId(11L, 555L));
        doThrow(new RedisConnectionFailureException("Redis 연결 실패"))
                .when(refreshTokenStore).save(anyString(), anyLong(), any());

        assertThatThrownBy(() -> kakaoLoginService.login("token"))
                .isInstanceOf(RedisConnectionFailureException.class);
    }

    @Test
    void Kakao_토큰이_invalid하면_예외가_그대로_전파된다() {
        when(kakaoUserInfoClient.getUserInfo("bad-token"))
                .thenThrow(new KakaoTokenInvalidException("Kakao access token이 유효하지 않습니다."));

        assertThatThrownBy(() -> kakaoLoginService.login("bad-token"))
                .isInstanceOf(KakaoTokenInvalidException.class);
    }

    @Test
    void Kakao_API_자체가_실패하면_예외가_그대로_전파된다() {
        when(kakaoUserInfoClient.getUserInfo("token"))
                .thenThrow(new KakaoApiException("Kakao 사용자 정보 조회에 실패했습니다."));

        assertThatThrownBy(() -> kakaoLoginService.login("token"))
                .isInstanceOf(KakaoApiException.class);
    }

    // MySQL이 실제로 던지는 중복 키 오류(1062)를 그대로 흉내낸다 - 메시지에 충돌한 값을
    // 그대로 echo하는 실제 동작(KakaoUserConstraintDiagnosticIT로 경험적으로 확인)을 재현한다.
    private DataIntegrityViolationException duplicateEntryViolation(String duplicatedValue) {
        SQLIntegrityConstraintViolationException sqlException = new SQLIntegrityConstraintViolationException(
                "Duplicate entry '" + duplicatedValue + "' for key 'users.UKxxxxxxxxxxxxxxxxxxxxxxxxx'",
                "23000", 1062
        );
        return new DataIntegrityViolationException("insert 실패", sqlException);
    }

    // 실제 장애 재현: nickname NOT NULL 위반(MySQL 1048, "Column 'nickname' cannot be null").
    private DataIntegrityViolationException notNullViolation() {
        SQLIntegrityConstraintViolationException sqlException = new SQLIntegrityConstraintViolationException(
                "Column 'nickname' cannot be null", "23000", 1048
        );
        return new DataIntegrityViolationException("insert 실패", sqlException);
    }

    // 동시 최초 로그인 race: findOrCreate()가 kakao_user_id UNIQUE 위반으로 실패해도 재조회로
    // 흡수해 로그인 자체는 성공해야 한다(AuctionLikeService와 동일한 catch/retry 패턴).
    @Test
    void 동시_최초_로그인_race가_발생해도_기존_User로_로그인에_성공한다() {
        KakaoUserInfo info = new KakaoUserInfo(777L, "race@example.com", "레이스", null);
        when(kakaoUserInfoClient.getUserInfo("token")).thenReturn(info);
        when(kakaoUserFindOrCreateService.findOrCreate(info))
                .thenThrow(duplicateEntryViolation("777"));
        when(kakaoUserFindOrCreateService.getByKakaoUserId(777L)).thenReturn(userWithId(88L, 777L));

        KakaoLoginResponse response = kakaoLoginService.login("token");

        assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()).userId()).isEqualTo(88L);
    }

    // 실제 장애 재현: Kakao가 nickname을 주지 않아 NOT NULL 위반이 났던 경우(수정 전에는
    // 이것도 "동시 가입 race"로 오판해 getByKakaoUserId()를 호출했고, 커밋된 행이 없으니
    // IllegalStateException이 500으로 노출됐다). kakao_user_id 충돌이 아니므로 재조회하지
    // 않고 원본 예외가 그대로 전파되어야 한다.
    @Test
    void nickname_NOT_NULL_위반은_race로_취급하지_않고_원본_예외를_전파한다() {
        KakaoUserInfo info = new KakaoUserInfo(888L, null, null, null);
        when(kakaoUserInfoClient.getUserInfo("token")).thenReturn(info);
        when(kakaoUserFindOrCreateService.findOrCreate(info)).thenThrow(notNullViolation());

        assertThatThrownBy(() -> kakaoLoginService.login("token"))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(kakaoUserFindOrCreateService, never()).getByKakaoUserId(any());
    }

    // email UNIQUE 위반(서로 다른 kakaoUserId가 같은 email을 가진 경우)도 1062지만
    // kakao_user_id 충돌이 아니다 - 재조회하면 당연히 못 찾으므로 오판하지 않고 원본 예외를
    // 그대로 전파해야 한다.
    @Test
    void email_UNIQUE_위반은_race로_취급하지_않고_원본_예외를_전파한다() {
        KakaoUserInfo info = new KakaoUserInfo(999L, "dup@example.com", "닉네임", null);
        when(kakaoUserInfoClient.getUserInfo("token")).thenReturn(info);
        when(kakaoUserFindOrCreateService.findOrCreate(info))
                .thenThrow(duplicateEntryViolation("dup@example.com"));

        assertThatThrownBy(() -> kakaoLoginService.login("token"))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(kakaoUserFindOrCreateService, never()).getByKakaoUserId(any());
    }
}
