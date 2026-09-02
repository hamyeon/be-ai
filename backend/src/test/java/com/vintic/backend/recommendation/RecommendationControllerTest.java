package com.vintic.backend.recommendation;

import com.vintic.backend.recommendation.dto.RecommendationResponse;
import com.vintic.backend.recommendation.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #75-5A freeze audit: RecommendationController가 X-User-Id 헤더를 직접 읽던 경로를 제거하고
// AuctionController의 anonymous GET 3개와 동일하게 currentUserId request attribute만 신뢰하도록
// 고쳤다. 이 attribute를 채우는 실제 메커니즘(local=MockAuthInterceptor, dev/prod=
// JwtAuthenticationFilter)은 MockAuthInterceptorTest/JwtAnonymousEndpointSecurityTest/
// JwtAuthorizationWiringMySqlIT가 이미 검증하므로, 여기서는 AuctionControllerTest와 동일한
// 패턴으로 "컨트롤러가 attribute만 신뢰하고 X-User-Id는 완전히 무시한다"만 확인한다.
@WebMvcTest(RecommendationController.class)
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendationService recommendationService;

    private RecommendationResponse fallbackResponse() {
        return new RecommendationResponse(false, "지금 인기 있는 경매입니다.", List.of());
    }

    private RecommendationResponse personalizedResponse() {
        return new RecommendationResponse(true, "최근 보신 상품과 비슷한 경매를 골랐습니다.", List.of());
    }

    // 시나리오 1/6: 토큰/헤더가 전혀 없으면(local/dev/prod 공통) anonymous로 처리된다.
    @Test
    void 아무_identity도_없으면_anonymous로_추천된다() throws Exception {
        when(recommendationService.recommend(isNull(), anyInt())).thenReturn(fallbackResponse());

        mockMvc.perform(get("/api/recommendations/auctions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.personalized").value(false));

        verify(recommendationService).recommend(null, 10);
    }

    // 시나리오 2/5: currentUserId request attribute가 있으면(local=MockAuth, dev/prod=JWT
    // 어느 쪽이 채웠든) 그 userId로 개인화된다.
    @Test
    void currentUserId_attribute가_있으면_해당_유저로_개인화된다() throws Exception {
        when(recommendationService.recommend(eq(2L), anyInt())).thenReturn(personalizedResponse());

        mockMvc.perform(get("/api/recommendations/auctions").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.personalized").value(true));

        verify(recommendationService).recommend(2L, 10);
    }

    // 시나리오 3: dev/prod에서 JWT 없이 X-User-Id만 보내도(= currentUserId attribute가 없는
    // 상태) 완전히 무시되고 anonymous로 처리된다.
    @Test
    void X_User_Id_헤더만_보내면_무시되고_익명으로_처리된다() throws Exception {
        when(recommendationService.recommend(isNull(), anyInt())).thenReturn(fallbackResponse());

        mockMvc.perform(get("/api/recommendations/auctions").header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(recommendationService).recommend(null, 10);
    }

    // 시나리오 4: valid JWT(=currentUserId attribute)와 다른 X-User-Id 헤더가 함께 와도
    // attribute만 신뢰하고 header는 무시된다.
    @Test
    void currentUserId_attribute와_다른_X_User_Id_헤더가_함께_오면_attribute가_우선한다() throws Exception {
        when(recommendationService.recommend(eq(2L), anyInt())).thenReturn(personalizedResponse());

        mockMvc.perform(get("/api/recommendations/auctions")
                        .requestAttr("currentUserId", 2L)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(recommendationService).recommend(2L, 10);
    }
}
