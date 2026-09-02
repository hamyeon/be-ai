package com.vintic.backend.auth.kakao;

// Kakao 사용자 정보 API 응답을 이 내부 DTO로만 노출한다 - Kakao 원본 응답 타입
// (KakaoUserInfoResponse)은 이 패키지 밖으로 나가지 않는다.
public record KakaoUserInfo(Long kakaoUserId, String email, String nickname, String profileImageUrl) {
}
