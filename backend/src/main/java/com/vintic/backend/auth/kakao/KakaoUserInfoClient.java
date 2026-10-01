package com.vintic.backend.auth.kakao;

import com.vintic.backend.common.exception.KakaoApiException;
import com.vintic.backend.common.exception.KakaoTokenInvalidException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

// Kakao 사용자 정보 조회 전용 adapter(#75-4C). 기존 RestTemplateConfig의 RestTemplate 빈을
// 재사용한다(OpenAiVisionClient와 동일한 관례) - 새 HTTP 클라이언트 의존성을 추가하지 않는다.
// Kakao 앱 자체의 REST API 키/secret은 필요 없다 - 이 호출은 "이미 프론트가 받아온 사용자의
// Kakao access token"을 그대로 Bearer로 전달해 신원만 확인하는 것이라, 여기엔 커밋 금지 대상
// secret이 없다.
//
// Kakao의 모든 4xx를 KAKAO_TOKEN_INVALID로 매핑하지 않는다 - 실제 401(invalid/expired token)만
// KakaoTokenInvalidException, 그 외(다른 4xx/5xx/timeout/network)는 전부 KakaoApiException으로
// 수렴한다(#75-4C 사용자 확정).
@Component
@Slf4j
public class KakaoUserInfoClient {

    private static final String KAKAO_USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private final RestTemplate restTemplate;

    public KakaoUserInfoClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public KakaoUserInfo getUserInfo(String kakaoAccessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(kakaoAccessToken);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<KakaoUserInfoResponse> response;
        try {
            response = restTemplate.exchange(
                    KAKAO_USER_INFO_URL, HttpMethod.GET, request, KakaoUserInfoResponse.class
            );
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new KakaoTokenInvalidException("Kakao access token이 유효하지 않습니다.", e);
        } catch (RestClientException e) {
            log.warn("Kakao 사용자 정보 조회 실패", e);
            throw new KakaoApiException("Kakao 사용자 정보 조회에 실패했습니다.", e);
        }

        KakaoUserInfoResponse body = response.getBody();
        if (body == null || body.id() == null) {
            throw new KakaoApiException("Kakao 사용자 정보 응답이 올바르지 않습니다.");
        }

        KakaoUserInfoResponse.KakaoAccount account = body.kakaoAccount();
        KakaoUserInfoResponse.KakaoAccount.Profile profile = account != null ? account.profile() : null;

        return new KakaoUserInfo(
                body.id(),
                account != null ? account.email() : null,
                profile != null ? profile.nickname() : null,
                profile != null ? profile.profileImageUrl() : null
        );
    }
}
