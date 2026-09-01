package com.vintic.backend.recommendation.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.dto.RecommendationResponse;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationServiceTest {

    @Mock
    private UserActivityLogRepository activityLogRepository;

    @Mock
    private UserVectorService userVectorService;

    @Mock
    private ProductVectorRepository productVectorRepository;

    @Mock
    private AuctionRepository auctionRepository;

    // Fallback 생성은 FallbackRecommendationProviderTest가 검증한다.
    // 여기서는 개인화/Fallback 분기만 본다.
    @Mock
    private FallbackRecommendationProvider fallbackProvider;

    private RecommendationService newService() {
        return new RecommendationService(activityLogRepository, userVectorService,
                productVectorRepository, auctionRepository, fallbackProvider);
    }

    private static final float[] NIKE_LIKE = {1.0f, 0.0f};
    private static final float[] ADIDAS_LIKE = {0.0f, 1.0f};

    private Auction auction(Long auctionId, Long productId) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        Auction auction = mock(Auction.class);
        when(auction.getId()).thenReturn(auctionId);
        when(auction.getProduct()).thenReturn(product);
        when(auction.getEndAt()).thenReturn(LocalDateTime.now().plusHours(1));
        return auction;
    }

    // Fallback 내용 검증은 FallbackRecommendationProviderTest의 몫이다.
    //
    // Auction 목을 받지 않고 id만 받는다. 목의 getter를 when(...) 안에서 부르면
    // Mockito가 스터빙 중인 것으로 오해해 UnfinishedStubbingException을 던진다.
    private RecommendationResponse fallbackWith(long auctionId, long productId) {
        return new RecommendationResponse(false, "Fallback",
                List.of(new RecommendationResponse.RecommendedAuction(
                        auctionId, productId, "Nike", "Model", "Color", 270, 100_000L, null, null)));
    }

    @Test
    void 행동이_3건_미만이면_개인화하지_않는다() {
        // 1~2건은 우연일 수 있어 취향이라 보기 어렵다
        Auction a10 = auction(10L, 100L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(2L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isFalse();
        verify(userVectorService, never()).buildUserVector(anyLong());
    }

    @Test
    void 행동이_3건_이상이면_취향_유사도로_정렬한다() {
        Auction a10 = auction(10L, 100L);
        Auction a11 = auction(11L, 200L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(3L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList())).thenReturn(List.of(a10, a11));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of(
                ProductVector.of(100L, ADIDAS_LIKE, "adidas"),
                ProductVector.of(200L, NIKE_LIKE, "nike")));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isTrue();
        // 취향(NIKE_LIKE)과 같은 방향인 상품 200이 먼저 와야 한다
        assertThat(response.items().get(0).productId()).isEqualTo(200L);
        assertThat(response.items().get(0).similarity()).isGreaterThan(response.items().get(1).similarity());
    }

    @Test
    void 비로그인이면_Fallback을_준다() {
        Auction a10 = auction(10L, 100L);
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        RecommendationResponse response = newService().recommend(null, 10);

        assertThat(response.personalized()).isFalse();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).similarity()).isNull();
    }

    @Test
    void 취향은_있지만_추천할_경매가_없으면_Fallback으로_넘어간다() {
        // 벡터가 아직 안 만들어진 상품만 있는 경우 - 빈 목록을 주면 화면이 깨진다
        Auction a10 = auction(10L, 100L);
        Auction a10Fallback = auction(10L, 100L);
        when(activityLogRepository.countByUserId(1L)).thenReturn(5L);
        when(userVectorService.buildUserVector(1L)).thenReturn(Optional.of(NIKE_LIKE));
        when(auctionRepository.findOpenAuctions(anyList())).thenReturn(List.of(a10));
        when(productVectorRepository.findAllById(anyList())).thenReturn(List.of());
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        RecommendationResponse response = newService().recommend(1L, 10);

        assertThat(response.personalized()).isFalse();
        assertThat(response.items()).isNotEmpty();
    }

    @Test
    void limit을_Fallback에_그대로_넘긴다() {
        // 개수를 실제로 잘라내는 건 FallbackRecommendationProviderTest가 검증한다.
        // 여기서 볼 것은 요청받은 limit이 손실 없이 전달되는지다.
        when(fallbackProvider.recommend(anyInt())).thenReturn(fallbackWith(10L, 100L));

        newService().recommend(null, 7);

        verify(fallbackProvider).recommend(7);
    }
}
