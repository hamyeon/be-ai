package com.vintic.backend.auth;

import com.vintic.backend.auth.dto.KakaoLoginRequest;
import com.vintic.backend.auth.dto.KakaoLoginResponse;
import com.vintic.backend.auth.dto.LogoutRequest;
import com.vintic.backend.auth.dto.RefreshRequest;
import com.vintic.backend.auth.dto.RefreshResponse;
import com.vintic.backend.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// #75-4C/4D, dev/prod 전용(JwtTokenProvider에 의존하므로 local/test에는 이 endpoint 자체가
// 없다 - local은 MockAuth를 계속 쓴다). 세 endpoint 모두 anonymous - JwtSecurityConfig가
// /api/auth/**를 permitAll하고, JwtAuthenticationFilter도 이 경로에는 Access Token Bearer
// 검증을 적용하지 않는다(§8) - 각 endpoint의 실제 인증은 요청 바디의 credential(Kakao
// access token/Refresh JWT) 자체가 source of truth다.
@RestController
@RequestMapping("/api/auth")
@Profile({"dev", "prod"})
public class AuthController {

    private final KakaoLoginService kakaoLoginService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(KakaoLoginService kakaoLoginService, RefreshTokenService refreshTokenService) {
        this.kakaoLoginService = kakaoLoginService;
        this.refreshTokenService = refreshTokenService;
    }

    @Operation(
            summary = "Kakao 로그인",
            description = "Kakao access token으로 사용자 신원을 확인하고, 내부 User를 find-or-create한 뒤 "
                    + "자체 Access/Refresh JWT를 발급한다. Kakao는 이 호출에서만 쓰이고, 이후 API 인증은 "
                    + "응답의 accessToken(Bearer)만 사용한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "accessToken 누락(40001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Kakao access token이 유효하지 않음(40102)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Kakao upstream 실패(50201)")
    })
    @PostMapping("/kakao")
    public ResponseEntity<ApiResponse<KakaoLoginResponse>> loginWithKakao(
            @Valid @RequestBody KakaoLoginRequest request
    ) {
        KakaoLoginResponse response = kakaoLoginService.login(request.accessToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "Access Token 재발급",
            description = "기존 Refresh Token을 검증하고 새 Access Token만 발급한다. Refresh Token 자체는 "
                    + "재발급하지 않는다(rotation 없음, 이번 범위)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "refreshToken 누락(40001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "유효하지 않은 Refresh Token(40103)")
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        RefreshResponse response = refreshTokenService.refresh(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(
            summary = "로그아웃",
            description = "제출한 Refresh Token의 Redis 등록을 삭제한다. 이미 삭제된(먼저 로그아웃했거나 "
                    + "만료된) 경우에도 토큰 자체가 유효하면 200으로 처리한다(idempotent). 이미 발급된 "
                    + "Access Token은 자체 만료 전까지는 계속 유효할 수 있다(blacklist 없음, 알려진 limitation)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공(또는 이미 로그아웃된 상태의 재확인)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "refreshToken 누락(40001)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "malformed/서명 불일치/만료/Access token 오사용(40103)")
    })
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        refreshTokenService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
