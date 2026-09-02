package com.vintic.backend.config;

import com.vintic.backend.auction.AuctionController;
import com.vintic.backend.auction.dto.AuctionDetailFixtures;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.auction.service.AuctionResultQueryService;
import com.vintic.backend.auth.jwt.JwtProperties;
import com.vintic.backend.auth.jwt.JwtTokenProvider;
import com.vintic.backend.auth.security.JwtAuthenticationEntryPoint;
import com.vintic.backend.auth.security.JwtAuthenticationFilter;
import com.vintic.backend.autobid.service.AutoBidQueryService;
import com.vintic.backend.autobid.service.AutoBidService;
import com.vintic.backend.config.ClockConfig;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.like.service.AuctionLikeService;
import com.vintic.backend.order.service.AuctionForfeitService;
import com.vintic.backend.recommendation.service.ActivityLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #75-4B: anonymous 허용 GET(GET /api/auctions/{id}, @RequestAttribute(value="currentUserId",
// required=false))이 dev/prod SecurityFilterChain 아래에서 §4 정책대로 동작하는지 검증한다.
// identity source는 SecurityContext/JWT뿐이어야 한다 - JwtAuthenticationFilter는 X-User-Id
// 헤더를 전혀 읽거나 합성하지 않으므로, 외부에서 보낸 X-User-Id는 값과 무관하게 항상 무시되고
// Authorization의 Access Token만 개인화에 반영되어야 한다(보안 마무리 수정).
@WebMvcTest(AuctionController.class)
@ActiveProfiles("dev")
@Import({
        JwtSecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        JwtTokenProvider.class, JwtProperties.class, ClockConfig.class
})
@TestPropertySource(properties = "jwt.secret=anonymous-endpoint-test-secret-32-bytes-minimum!!")
class JwtAnonymousEndpointSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private AuctionQueryService auctionQueryService;

    @MockitoBean
    private AuctionResultQueryService auctionResultQueryService;

    @MockitoBean
    private BidQueryService bidQueryService;

    @MockitoBean
    private ManualBidService manualBidService;

    @MockitoBean
    private AutoBidService autoBidService;

    @MockitoBean
    private AutoBidQueryService autoBidQueryService;

    @MockitoBean
    private AuctionLikeService auctionLikeService;

    @MockitoBean
    private AuctionForfeitService auctionForfeitService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @Test
    void 토큰없이_요청하면_익명으로_정상_응답한다() throws Exception {
        when(auctionQueryService.getAuctionDetail(eq(1L), isNull())).thenReturn(AuctionDetailFixtures.sample());

        mockMvc.perform(get("/api/auctions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 유효한_토큰이면_개인화된_userId로_조회된다() throws Exception {
        when(auctionQueryService.getAuctionDetail(eq(1L), eq(5L))).thenReturn(AuctionDetailFixtures.sample());
        String token = jwtTokenProvider.issueAccessToken(5L);

        mockMvc.perform(get("/api/auctions/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        verify(auctionQueryService).getAuctionDetail(1L, 5L);
    }

    @Test
    void 유효하지_않은_토큰이면_401과_40101을_반환한다() throws Exception {
        mockMvc.perform(get("/api/auctions/1").header("Authorization", "Bearer garbage-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 토큰없이_임의의_X_User_Id_헤더만_보내면_익명으로_처리된다() throws Exception {
        when(auctionQueryService.getAuctionDetail(eq(1L), isNull())).thenReturn(AuctionDetailFixtures.sample());

        mockMvc.perform(get("/api/auctions/1").header("X-User-Id", "999"))
                .andExpect(status().isOk());

        verify(auctionQueryService).getAuctionDetail(1L, null);
    }

    @Test
    void 유효한_토큰과_다른_X_User_Id_헤더가_함께오면_토큰의_userId가_우선하고_헤더는_무시된다() throws Exception {
        when(auctionQueryService.getAuctionDetail(eq(1L), eq(5L))).thenReturn(AuctionDetailFixtures.sample());
        String token = jwtTokenProvider.issueAccessToken(5L);

        mockMvc.perform(get("/api/auctions/1")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "999"))
                .andExpect(status().isOk());

        verify(auctionQueryService).getAuctionDetail(1L, 5L);
    }
}
