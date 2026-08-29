package com.vintic.backend.auction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.domain.CannotBidReason;
import com.vintic.backend.auction.dto.AuctionDetailResponse;
import com.vintic.backend.auction.dto.AuctionLiveResponse;
import com.vintic.backend.auction.service.AuctionQueryService;
import com.vintic.backend.autobid.domain.AutoBidSettingStatus;
import com.vintic.backend.autobid.dto.AutoBidCancelResponse;
import com.vintic.backend.autobid.dto.AutoBidMaxAmountRequest;
import com.vintic.backend.autobid.dto.AutoBidMeResponse;
import com.vintic.backend.autobid.dto.AutoBidRecommendationResponse;
import com.vintic.backend.autobid.dto.AutoBidRegisterResponse;
import com.vintic.backend.autobid.dto.AutoBidUpdateResponse;
import com.vintic.backend.autobid.service.AutoBidQueryService;
import com.vintic.backend.autobid.service.AutoBidService;
import com.vintic.backend.bid.domain.BidType;
import com.vintic.backend.bid.dto.BidHistoryResponse;
import com.vintic.backend.bid.dto.BidResponse;
import com.vintic.backend.bid.dto.PlaceBidRequest;
import com.vintic.backend.bid.dto.PlaceBidResponse;
import com.vintic.backend.bid.service.BidQueryService;
import com.vintic.backend.bid.service.ManualBidService;
import com.vintic.backend.common.exception.AlreadyHighestBidderException;
import com.vintic.backend.common.exception.AuctionClosedException;
import com.vintic.backend.common.exception.AuctionNotFoundException;
import com.vintic.backend.common.exception.AuctionNotStartedException;
import com.vintic.backend.common.exception.AutoBidAlreadyExistsException;
import com.vintic.backend.common.exception.AutoBidNotFoundException;
import com.vintic.backend.common.exception.BidAmountTooLowException;
import com.vintic.backend.common.exception.CapNotIncreasedException;
import com.vintic.backend.common.exception.CapTooLowException;
import com.vintic.backend.common.exception.PenaltyRestrictedException;
import com.vintic.backend.common.exception.SellerCannotBidException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import com.vintic.backend.recommendation.service.ActivityLogService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private ManualBidService manualBidService;

    @MockitoBean
    private AutoBidService autoBidService;

    @MockitoBean
    private AutoBidQueryService autoBidQueryService;

    // 조회/입찰 시 추천용 행동 로그를 남긴다. 기록 자체는 여기서 검증하지 않고
    // ActivityLogServiceTest가 담당하므로 빈만 채워둔다.
    @MockitoBean
    private ActivityLogService activityLogService;

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
        PlaceBidResponse response = new PlaceBidResponse(
                1L, 15000L, 15000L, 20000L, "bid****", true, false, false,
                OffsetDateTime.now().plusHours(1)
        );
        when(manualBidService.placeBid(1L, 2L, 15000L, "abc")).thenReturn(response);

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.bidId").value(1))
                .andExpect(jsonPath("$.data.submittedAmount").value(15000))
                .andExpect(jsonPath("$.data.currentPrice").value(15000))
                .andExpect(jsonPath("$.data.isHighestBidder").value(true))
                .andExpect(jsonPath("$.data.autoBidCanceled").value(false))
                .andExpect(jsonPath("$.data.proxyResponded").value(false));
    }

    @Test
    void 존재하지_않는_경매에_입찰하면_404를_반환한다() throws Exception {
        when(manualBidService.placeBid(eq(999L), anyLong(), anyLong(), anyString()))
                .thenThrow(new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: 999"));

        mockMvc.perform(post("/api/auctions/999/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 판매자_본인이_입찰하면_403과_40301을_반환한다() throws Exception {
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new SellerCannotBidException("판매자는 자신의 경매에 입찰할 수 없습니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40301));
    }

    @Test
    void 입찰_제한_기간중인_사용자면_403과_40302를_반환한다() throws Exception {
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new PenaltyRestrictedException("입찰 제한 기간 중인 사용자입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(40302));
    }

    @Test
    void 현재_최고입찰자가_추가_입찰하면_409와_40901을_반환한다() throws Exception {
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new AlreadyHighestBidderException("이미 현재 최고입찰자입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40901));
    }

    @Test
    void SCHEDULED_경매에_입찰하면_409와_40902를_반환한다() throws Exception {
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new AuctionNotStartedException("아직 시작되지 않은 경매입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40902));
    }

    @Test
    void 종료_취소된_경매에_입찰하면_409와_40903을_반환한다() throws Exception {
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new AuctionClosedException("이미 종료되었거나 취소된 경매입니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40903));
    }

    @Test
    void 최소금액_미만이면_409와_40904를_반환한다() throws Exception {
        when(manualBidService.placeBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new BidAmountTooLowException("입찰 금액은 15000원 이상이어야 합니다."));

        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(14999L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40904));
    }

    @Test
    void Idempotency_Key_헤더가_없으면_400과_40004를_반환한다() throws Exception {
        mockMvc.perform(post("/api/auctions/1/bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new PlaceBidRequest(15000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40004));
    }

    @Test
    void live_조회_성공시_200과_현재_상태를_반환한다() throws Exception {
        AuctionLiveResponse response = new AuctionLiveResponse(
                1L, AuctionStatus.LIVE, 105000L, 110000L, 5000L,
                "mma****", true, false, CannotBidReason.ALREADY_HIGHEST_BIDDER, null,
                OffsetDateTime.now().plusHours(1), OffsetDateTime.now(),
                AutoBidSettingStatus.ACTIVE, 120000L, 110000L
        );
        when(auctionQueryService.getLiveView(1L, 2L)).thenReturn(response);

        mockMvc.perform(get("/api/auctions/1/live").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.auctionId").value(1))
                .andExpect(jsonPath("$.data.currentPrice").value(105000))
                .andExpect(jsonPath("$.data.minNextBidAmount").value(110000))
                .andExpect(jsonPath("$.data.highestBidderMasked").value("mma****"))
                .andExpect(jsonPath("$.data.isMine").value(true))
                .andExpect(jsonPath("$.data.canBid").value(false))
                .andExpect(jsonPath("$.data.cannotBidReason").value("ALREADY_HIGHEST_BIDDER"))
                .andExpect(jsonPath("$.data.myAutoBidStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.myCap").value(120000))
                .andExpect(jsonPath("$.data.minCapAmount").value(110000));
    }

    @Test
    void 존재하지_않는_경매의_live_조회는_404를_반환한다() throws Exception {
        when(auctionQueryService.getLiveView(999L, 2L))
                .thenThrow(new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: 999"));

        mockMvc.perform(get("/api/auctions/999/live").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 추천_조회_성공시_200과_aiRecommendedCap을_반환한다() throws Exception {
        AutoBidRecommendationResponse response = new AutoBidRecommendationResponse(1L, 15000L, 10000L, 15000L, 5000L);
        when(auctionQueryService.getAutoBidRecommendation(1L)).thenReturn(response);

        mockMvc.perform(get("/api/auctions/1/auto-bid/recommendation").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.auctionId").value(1))
                .andExpect(jsonPath("$.data.aiRecommendedCap").value(15000))
                .andExpect(jsonPath("$.data.currentPrice").value(10000))
                .andExpect(jsonPath("$.data.minCapAmount").value(15000))
                .andExpect(jsonPath("$.data.bidIncrement").value(5000));
    }

    @Test
    void 존재하지_않는_경매의_추천_조회는_404를_반환한다() throws Exception {
        when(auctionQueryService.getAutoBidRecommendation(999L))
                .thenThrow(new AuctionNotFoundException("존재하지 않는 경매입니다. auctionId: 999"));

        mockMvc.perform(get("/api/auctions/999/auto-bid/recommendation").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40402));
    }

    @Test
    void 자동입찰_등록_성공시_201과_등록_결과를_반환한다() throws Exception {
        AutoBidRegisterResponse response = new AutoBidRegisterResponse(
                15L, 1L, AutoBidSettingStatus.RESERVED, 120000L, 50000L, 55000L, 55000L,
                OffsetDateTime.now().plusHours(1), false, null, false
        );
        when(autoBidService.createAutoBid(1L, 2L, 120000L, "abc")).thenReturn(response);

        mockMvc.perform(post("/api/auctions/1/auto-bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(120000L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.autoBidSettingId").value(15))
                .andExpect(jsonPath("$.data.status").value("RESERVED"))
                .andExpect(jsonPath("$.data.bidOccurred").value(false))
                .andExpect(jsonPath("$.data.resultingBidAmount").doesNotExist())
                .andExpect(jsonPath("$.data.isHighestBidder").value(false));
    }

    @Test
    void 자동입찰_등록시_상한가가_너무_낮으면_409와_40906을_반환한다() throws Exception {
        when(autoBidService.createAutoBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new CapTooLowException("자동입찰 상한가가 너무 낮습니다."));

        mockMvc.perform(post("/api/auctions/1/auto-bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(1000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40906));
    }

    @Test
    void 자동입찰_등록시_이미_설정이_있으면_409와_40908을_반환한다() throws Exception {
        when(autoBidService.createAutoBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new AutoBidAlreadyExistsException("이미 자동입찰이 등록되어 있습니다."));

        mockMvc.perform(post("/api/auctions/1/auto-bids")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(120000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40908));
    }

    @Test
    void 자동입찰_등록시_Idempotency_Key_헤더가_없으면_400과_40004를_반환한다() throws Exception {
        mockMvc.perform(post("/api/auctions/1/auto-bids")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(120000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40004));
    }

    @Test
    void 내_자동입찰_조회_성공시_200과_현재_설정을_반환한다() throws Exception {
        AutoBidMeResponse response = new AutoBidMeResponse(
                15L, 1L, AutoBidSettingStatus.ACTIVE, 120000L, 105000L, 110000L,
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now(), true, true
        );
        when(autoBidQueryService.getMyAutoBid(1L, 2L)).thenReturn(response);

        mockMvc.perform(get("/api/auctions/1/auto-bids/me").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoBidSettingId").value(15))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.canModify").value(true))
                .andExpect(jsonPath("$.data.canCancel").value(true));
    }

    @Test
    void 내_자동입찰이_없으면_404와_40404를_반환한다() throws Exception {
        when(autoBidQueryService.getMyAutoBid(1L, 2L))
                .thenThrow(new AutoBidNotFoundException("등록된 자동입찰이 없습니다."));

        mockMvc.perform(get("/api/auctions/1/auto-bids/me").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40404));
    }

    @Test
    void 자동입찰_수정_성공시_200과_수정_결과를_반환한다() throws Exception {
        AutoBidUpdateResponse response = new AutoBidUpdateResponse(
                15L, AutoBidSettingStatus.ACTIVE, 140000L, 105000L, 110000L, false, null, false
        );
        when(autoBidService.updateAutoBid(1L, 2L, 140000L, "abc")).thenReturn(response);

        mockMvc.perform(patch("/api/auctions/1/auto-bids/me")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(140000L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maxAmount").value(140000));
    }

    @Test
    void 자동입찰_수정시_상향하지_않으면_409와_40907을_반환한다() throws Exception {
        when(autoBidService.updateAutoBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new CapNotIncreasedException("상한가는 현재 설정값보다 높아야 합니다."));

        mockMvc.perform(patch("/api/auctions/1/auto-bids/me")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(100000L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value(40907));
    }

    @Test
    void 자동입찰_수정시_Idempotency_Key_헤더가_없으면_400과_40004를_반환한다() throws Exception {
        mockMvc.perform(patch("/api/auctions/1/auto-bids/me")
                        .requestAttr("currentUserId", 2L)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(140000L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(40004));
    }

    @Test
    void 자동입찰_수정시_설정이_없으면_404와_40404를_반환한다() throws Exception {
        when(autoBidService.updateAutoBid(anyLong(), anyLong(), anyLong(), anyString()))
                .thenThrow(new AutoBidNotFoundException("등록된 자동입찰이 없습니다."));

        mockMvc.perform(patch("/api/auctions/1/auto-bids/me")
                        .requestAttr("currentUserId", 2L)
                        .header("Idempotency-Key", "abc")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new AutoBidMaxAmountRequest(140000L))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40404));
    }

    @Test
    void 자동입찰_취소_성공시_200과_CANCELED를_반환한다() throws Exception {
        AutoBidCancelResponse response = new AutoBidCancelResponse(15L, AutoBidSettingStatus.CANCELED, OffsetDateTime.now());
        when(autoBidService.cancelAutoBid(1L, 2L)).thenReturn(response);

        mockMvc.perform(delete("/api/auctions/1/auto-bids/me").requestAttr("currentUserId", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.autoBidSettingId").value(15))
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    @Test
    void 자동입찰_취소시_설정이_없으면_404와_40404를_반환한다() throws Exception {
        when(autoBidService.cancelAutoBid(1L, 2L))
                .thenThrow(new AutoBidNotFoundException("등록된 자동입찰이 없습니다."));

        mockMvc.perform(delete("/api/auctions/1/auto-bids/me").requestAttr("currentUserId", 2L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(40404));
    }
}
