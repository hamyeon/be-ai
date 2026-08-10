package com.vintic.backend.auction;

import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.dto.BidResponse;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionController.class)
class AuctionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuctionQueryService auctionQueryService;

    @MockitoBean
    private BidQueryService bidQueryService;

    @Test
    void 경매_상세조회_성공시_200과_sellerId_bidCount를_포함한_경매_정보를_반환한다() throws Exception {
        AuctionDetailResponse response = new AuctionDetailResponse(
                1L, 10L, 100L, null, 10000L, 10000L, 5000L,
                LocalDateTime.now().plusHours(1), LocalDateTime.now().plusHours(2), AuctionStatus.SCHEDULED, 2L
        );
        when(auctionQueryService.getAuctionDetail(1L)).thenReturn(response);

        mockMvc.perform(get("/api/auctions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.productId").value(10))
                .andExpect(jsonPath("$.data.sellerId").value(100))
                .andExpect(jsonPath("$.data.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.data.currentWinnerId").doesNotExist())
                .andExpect(jsonPath("$.data.bidCount").value(2));
    }

    @Test
    void 존재하지_않는_경매를_조회하면_404를_반환한다() throws Exception {
        when(auctionQueryService.getAuctionDetail(999L))
                .thenThrow(new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: 999"));

        mockMvc.perform(get("/api/auctions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 입찰이력_조회_성공시_200과_page_정보를_포함한_입찰_목록을_반환한다() throws Exception {
        BidResponse bid = new BidResponse(1L, 2L, 15000L, BidType.MANUAL, LocalDateTime.now());
        BidHistoryResponse response = new BidHistoryResponse(List.of(bid), 0, 20, false);
        when(bidQueryService.getBidHistory(eq(1L), anyInt(), anyInt(), anyString())).thenReturn(response);

        mockMvc.perform(get("/api/auctions/1/bids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bids[0].amount").value(15000))
                .andExpect(jsonPath("$.data.bids[0].bidType").value("MANUAL"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 입찰이력_조회시_page_size_order_쿼리파라미터가_서비스로_전달된다() throws Exception {
        BidHistoryResponse response = new BidHistoryResponse(List.of(), 1, 5, false);
        when(bidQueryService.getBidHistory(1L, 1, 5, "oldest")).thenReturn(response);

        mockMvc.perform(get("/api/auctions/1/bids?page=1&size=5&order=oldest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(5));
    }

    @Test
    void 존재하지_않는_경매의_입찰이력을_조회하면_404를_반환한다() throws Exception {
        when(bidQueryService.getBidHistory(eq(999L), anyInt(), anyInt(), anyString()))
                .thenThrow(new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: 999"));

        mockMvc.perform(get("/api/auctions/999/bids"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }
}
