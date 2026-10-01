package com.vintic.backend.auth.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Kakao GET /v2/user/me 원본 응답 형태 - 이 패키지 밖으로 노출하지 않는다(KakaoUserInfoClient만
// 사용, 서비스/도메인은 KakaoUserInfo만 본다). 필요한 필드만 매핑한다.
@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoUserInfoResponse(
        @JsonProperty("id") Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record KakaoAccount(
            @JsonProperty("email") String email,
            @JsonProperty("profile") Profile profile
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Profile(
                @JsonProperty("nickname") String nickname,
                @JsonProperty("profile_image_url") String profileImageUrl
        ) {
        }
    }
}
