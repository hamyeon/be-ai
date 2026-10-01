package com.vintic.backend.auth.kakao;

import com.vintic.backend.common.exception.KakaoApiException;
import com.vintic.backend.common.exception.KakaoTokenInvalidException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

// #75-4C: 실제 Kakao network를 호출하지 않는다 - MockRestServiceServer로 RestTemplate 호출을
// 가로챈다(OpenAiVisionClient가 이미 재사용 중인 것과 동일한 RestTemplate을 이 클라이언트도
// 쓴다는 전제하에, 여기서는 직접 만든 RestTemplate 인스턴스에 바인딩한다).
class KakaoUserInfoClientTest {

    private static final String KAKAO_USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private KakaoUserInfoClient kakaoUserInfoClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        kakaoUserInfoClient = new KakaoUserInfoClient(restTemplate);
    }

    @Test
    void 정상_응답이면_KakaoUserInfo로_변환된다() {
        mockServer.expect(requestTo(KAKAO_USER_INFO_URL))
                .andExpect(header("Authorization", "Bearer valid-kakao-token"))
                .andRespond(withSuccess("""
                        {
                          "id": 123456789,
                          "kakao_account": {
                            "email": "user@example.com",
                            "profile": {
                              "nickname": "홍길동",
                              "profile_image_url": "https://example.com/p.jpg"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        KakaoUserInfo info = kakaoUserInfoClient.getUserInfo("valid-kakao-token");

        assertThat(info.kakaoUserId()).isEqualTo(123456789L);
        assertThat(info.email()).isEqualTo("user@example.com");
        assertThat(info.nickname()).isEqualTo("홍길동");
        assertThat(info.profileImageUrl()).isEqualTo("https://example.com/p.jpg");
    }

    @Test
    void kakao_account가_없어도_kakaoUserId만으로_처리된다() {
        mockServer.expect(requestTo(KAKAO_USER_INFO_URL))
                .andRespond(withSuccess("{\"id\": 999}", MediaType.APPLICATION_JSON));

        KakaoUserInfo info = kakaoUserInfoClient.getUserInfo("token");

        assertThat(info.kakaoUserId()).isEqualTo(999L);
        assertThat(info.email()).isNull();
        assertThat(info.nickname()).isNull();
        assertThat(info.profileImageUrl()).isNull();
    }

    @Test
    void Kakao가_401을_반환하면_KakaoTokenInvalidException이_발생한다() {
        mockServer.expect(requestTo(KAKAO_USER_INFO_URL))
                .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> kakaoUserInfoClient.getUserInfo("invalid-token"))
                .isInstanceOf(KakaoTokenInvalidException.class);
    }

    @Test
    void Kakao가_5xx를_반환하면_KakaoApiException이_발생한다() {
        mockServer.expect(requestTo(KAKAO_USER_INFO_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> kakaoUserInfoClient.getUserInfo("token"))
                .isInstanceOf(KakaoApiException.class);
    }

    @Test
    void Kakao가_예상치_못한_4xx를_반환해도_40102가_아니라_KakaoApiException으로_수렴한다() {
        mockServer.expect(requestTo(KAKAO_USER_INFO_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> kakaoUserInfoClient.getUserInfo("token"))
                .isInstanceOf(KakaoApiException.class);
    }

    @Test
    void network_실패도_KakaoApiException으로_수렴한다() {
        mockServer.expect(requestTo(KAKAO_USER_INFO_URL))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        assertThatThrownBy(() -> kakaoUserInfoClient.getUserInfo("token"))
                .isInstanceOf(KakaoApiException.class);
    }
}
