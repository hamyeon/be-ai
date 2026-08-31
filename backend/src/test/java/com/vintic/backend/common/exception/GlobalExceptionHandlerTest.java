package com.vintic.backend.common.exception;

import com.vintic.backend.auction.AuctionController;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.auction.service.AuctionResultQueryService;
import com.vintic.backend.autobid.service.AutoBidQueryService;
import com.vintic.backend.autobid.service.AutoBidService;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.like.service.AuctionLikeService;
import com.vintic.backend.order.service.AuctionForfeitService;
import com.vintic.backend.recommendation.service.ActivityLogService;
import com.vintic.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 포괄 예외 핸들러가 덮어버리면 안 되는 응답을 지킨다.
@WebMvcTest(AuctionController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionQueryService auctionQueryService;

    @MockitoBean
    private AuctionResultQueryService auctionResultQueryService;

    @MockitoBean
    private BidQueryService bidQueryService;

    @MockitoBean
    private ManualBidService manualBidService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AutoBidService autoBidService;

    @MockitoBean
    private AutoBidQueryService autoBidQueryService;

    @MockitoBean
    private AuctionLikeService auctionLikeService;

    @MockitoBean
    private AuctionForfeitService auctionForfeitService;

    @Test
    void 존재하지_않는_경로는_404를_반환한다() {
        // Exception 포괄 핸들러가 잡으면 500이 나간다. 그러면 오타 난 URL과 서버 장애가
        // 응답으로 구분되지 않아, 헬스체크가 경로를 잘못 치면 "앱이 죽었다"로 읽힌다.
        assertPathNotFound("/actuator/env");
    }

    @Test
    void 오타난_API_경로도_404를_반환한다() {
        assertPathNotFound("/api/auctionss/1");
    }

    private void assertPathNotFound(String path) {
        try {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value(40400));
        } catch (Exception e) {
            throw new AssertionError("요청 수행에 실패했습니다: " + path, e);
        }
    }
}
