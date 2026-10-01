package com.vintic.backend.recommendation.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.dto.RecommendationResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallbackRecommendationProviderTest {

    @Mock
    private AuctionRepository auctionRepository;

    private FallbackRecommendationProvider newProvider() {
        return new FallbackRecommendationProvider(auctionRepository);
    }

    private Auction auction(long id, String brand) {
        Product product = new Product(null, List.of("https://example.com/a.jpg"), brand, "Model",
                "Color", 270, "A", "FULL", 100000, 100000, null, null, null, null);
        ReflectionTestUtils.setField(product, "id", id);
        Auction auction = Auction.schedule(product, 100_000L, 5_000L,
                LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(3));
        ReflectionTestUtils.setField(auction, "id", id);
        ReflectionTestUtils.setField(auction, "status", AuctionStatus.LIVE);
        return auction;
    }

    @Test
    void 마감_임박과_인기를_번갈아_섞는다() {
        // 마감 임박만 쓰면 품질이 들쭉날쭉하고, 인기만 쓰면 항상 같은 경매가 위에 남는다.
        Auction ending1 = auction(1L, "Nike");
        Auction ending2 = auction(2L, "Nike");
        Auction popular1 = auction(3L, "Adidas");
        Auction popular2 = auction(4L, "Adidas");
        when(auctionRepository.findEndingSoon(any(), any(), any())).thenReturn(List.of(ending1, ending2));
        when(auctionRepository.findPopular(anyList(), any())).thenReturn(List.of(popular1, popular2));

        RecommendationResponse response = newProvider().recommend(4);

        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(1L, 3L, 2L, 4L);
    }

    @Test
    void 양쪽에_같은_경매가_있어도_한_번만_넣는다() {
        Auction shared = auction(1L, "Nike");
        Auction other = auction(2L, "Adidas");
        when(auctionRepository.findEndingSoon(any(), any(), any())).thenReturn(List.of(shared));
        when(auctionRepository.findPopular(anyList(), any())).thenReturn(List.of(shared, other));

        RecommendationResponse response = newProvider().recommend(5);

        assertThat(response.items()).extracting(RecommendationResponse.RecommendedAuction::auctionId)
                .containsExactly(1L, 2L);
    }

    @Test
    void limit을_넘지_않는다() {
        when(auctionRepository.findEndingSoon(any(), any(), any()))
                .thenReturn(List.of(auction(1L, "Nike"), auction(2L, "Nike")));
        when(auctionRepository.findPopular(anyList(), any()))
                .thenReturn(List.of(auction(3L, "Adidas"), auction(4L, "Adidas")));

        assertThat(newProvider().recommend(3).items()).hasSize(3);
    }

    @Test
    void Fallback_결과에는_유사도가_없다() {
        // 프론트가 개인화 결과와 구분할 수 있어야 한다.
        when(auctionRepository.findEndingSoon(any(), any(), any())).thenReturn(List.of(auction(1L, "Nike")));
        when(auctionRepository.findPopular(anyList(), any())).thenReturn(List.of());

        RecommendationResponse response = newProvider().recommend(5);

        assertThat(response.personalized()).isFalse();
        assertThat(response.items()).allSatisfy(item ->
                assertThat(item.similarity()).isNull());
    }

    @Test
    void 경매가_없어도_빈_목록으로_정상_응답한다() {
        // 추천 자리가 비면 화면이 깨진다. 예외를 던지지 않는다.
        when(auctionRepository.findEndingSoon(any(), any(), any())).thenReturn(List.of());
        when(auctionRepository.findPopular(anyList(), any())).thenReturn(List.of());

        RecommendationResponse response = newProvider().recommend(5);

        assertThat(response.items()).isEmpty();
        assertThat(response.reason()).isNotBlank();
    }
}
