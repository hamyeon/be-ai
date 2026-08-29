package com.vintic.backend.common.auth.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.auction.AuctionController;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.autobid.service.AutoBidQueryService;
import com.vintic.backend.autobid.service.AutoBidService;
import com.vintic.backend.bid.dto.PlaceBidRequest;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.recommendation.service.ActivityLogService;
import com.vintic.backend.config.MockAuthWebConfig;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// MockAuthInterceptor가 실제로 걸리는지 확인하는 슬라이스 테스트.
// 입찰 API(currentUserId를 받는 핸들러)로 헤더 검증을 확인하고, 경매 조회 API(받지 않는 핸들러)로
// 헤더 없이도 열려 있는지 함께 확인한다.
@WebMvcTest(AuctionController.class)
@Import({MockAuthWebConfig.class, MockUserRegistry.class})
@ActiveProfiles("local")
class MockAuthInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AuctionQueryService auctionQueryService;

    @MockitoBean
    private BidQueryService bidQueryService;

    @MockitoBean
    private ManualBidService manualBidService;

    @MockitoBean
    private AutoBidService autoBidService;

    @MockitoBean
    private AutoBidQueryService autoBidQueryService;

    // 경매 조회/입찰은 추천용 행동 로그를 남긴다. 인증 검증에는 영향이 없어 목으로 둔다.
    @MockitoBean
    private ActivityLogService activityLogService;

    @Test
    void 헤더가_없으면_401을_반환한다() throws Exception {
        mockMvc.perform(placeBid())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 헤더가_숫자가_아니면_401을_반환한다() throws Exception {
        mockMvc.perform(placeBid().header("X-User-Id", "abc"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 존재하지_않는_유저면_401을_반환한다() throws Exception {
        when(userRepository.existsById(999L)).thenReturn(false);

        mockMvc.perform(placeBid().header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(40101));
    }

    @Test
    void 존재하는_유저면_통과한다() throws Exception {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new PlaceBidResponse(
                        1L, 15000L, 15000L, 20000L, "bid****", true, false, false, OffsetDateTime.now().plusHours(1)
                ));

        mockMvc.perform(placeBid().header("X-User-Id", "1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void currentUserId가_필요없는_API는_헤더가_없어도_통과한다() throws Exception {
        when(auctionQueryService.getAuctionDetail(1L)).thenReturn(new AuctionDetailResponse(
                1L, 10L, 100L, null, 10000L, 10000L, 5000L,
                LocalDateTime.now(), LocalDateTime.now().plusHours(1), AuctionStatus.LIVE, 0L
        ));

        mockMvc.perform(get("/api/auctions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    private MockHttpServletRequestBuilder placeBid() throws Exception {
        return post("/api/auctions/1/bids")
                .contentType(MediaType.APPLICATION_JSON)
                // 입찰 API가 요구하는 필수 헤더. 이 테스트의 관심사는 인증이므로 고정값으로 채운다.
                .header("Idempotency-Key", "test-key")
                .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L)));
    }
}
