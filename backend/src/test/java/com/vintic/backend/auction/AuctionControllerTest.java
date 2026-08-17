package com.vintic.backend.auction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.dto.BidResponse;
import com.vintic.backend.bid.dto.PlaceBidRequest;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidCommandService;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.common.exception.AlreadyHighestBidderException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AuctionNotStartedException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuctionController.class)
class AuctionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuctionQueryService auctionQueryService;

    @MockitoBean
    private BidQueryService bidQueryService;

    @MockitoBean
    private BidCommandService bidCommandService;

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

    @Test
    void 입찰_성공시_201과_PlaceBidResponse를_반환한다() throws Exception {
        PlaceBidResponse response = new PlaceBidResponse(1L, 1L, 15000L, 15000L, 2L, LocalDateTime.now());
        when(bidCommandService.placeManualBid(1L, 2L, 15000L)).thenReturn(response);

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bidId").value(1))
                .andExpect(jsonPath("$.data.auctionId").value(1))
                .andExpect(jsonPath("$.data.submittedAmount").value(15000))
                .andExpect(jsonPath("$.data.currentPrice").value(15000))
                .andExpect(jsonPath("$.data.currentWinnerId").value(2));
    }

    @Test
    void 존재하지_않는_경매에_입찰하면_404를_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(eq(999L), anyLong(), anyLong()))
                .thenThrow(new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: 999"));

        mockMvc.perform(post("/api/auctions/999/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 판매자_본인이_입찰하면_403과_40301을_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(anyLong(), anyLong(), anyLong()))
                .thenThrow(new SellerCannotBidException("판매자는 자신의 경매에 입찰할 수 없습니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40301));
    }

    @Test
    void 입찰_제한_기간중인_사용자면_403과_40302를_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(anyLong(), anyLong(), anyLong()))
                .thenThrow(new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40302));
    }

    @Test
    void 현재_최고입찰자가_추가_입찰하면_409와_40901을_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(anyLong(), anyLong(), anyLong()))
                .thenThrow(new AlreadyHighestBidderException("이미 현재 최고입찰자입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40901));
    }

    @Test
    void SCHEDULED_경매에_입찰하면_409와_40902를_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(anyLong(), anyLong(), anyLong()))
                .thenThrow(new AuctionNotStartedException("아직 시작되지 않은 경매입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40902));
    }

    @Test
    void 종료_취소된_경매에_입찰하면_409와_40903을_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(anyLong(), anyLong(), anyLong()))
                .thenThrow(new AuctionClosedException("이미 종료되었거나 취소된 경매입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40903));
    }

    @Test
    void 최소금액_미만이면_409와_40904를_반환한다() throws Exception {
        when(bidCommandService.placeManualBid(anyLong(), anyLong(), anyLong()))
                .thenThrow(new BidAmountTooLowException("입찰 금액은 15000원 이상이어야 합니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(14999L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40904));
    }
}
