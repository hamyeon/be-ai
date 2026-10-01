package com.vintic.backend.recommendation.service;

import com.vintic.backend.ai.search.embedding.CosineSimilarity;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserVectorServiceTest {

    @Mock
    private UserActivityLogRepository activityLogRepository;

    @Mock
    private ProductVectorRepository productVectorRepository;

    @Mock
    private AuctionRepository auctionRepository;

    private UserVectorService newService() {
        return new UserVectorService(activityLogRepository, productVectorRepository, auctionRepository);
    }

    // 축이 다른 두 방향 벡터. 가중치가 어느 쪽으로 기우는지 눈으로 확인하기 쉽다.
    private static final float[] NIKE_LIKE = {1.0f, 0.0f};
    private static final float[] ADIDAS_LIKE = {0.0f, 1.0f};

    private void givenLogs(List<UserActivityLog> logs) {
        when(activityLogRepository.findByUserIdOrderByCreatedAtDesc(eq(1L), any(Pageable.class)))
                .thenReturn(logs);
    }

    private void givenVectors(List<ProductVector> vectors) {
        when(productVectorRepository.findAllById(any())).thenReturn(vectors);
    }

    @Test
    void 행동_기록이_없으면_취향_벡터가_없다() {
        // 호출부는 이때 Cold Start Fallback으로 넘어간다
        givenLogs(List.of());

        assertThat(newService().buildUserVector(1L)).isEmpty();
    }

    @Test
    void 본_상품들의_평균이_취향_벡터가_된다() {
        givenLogs(List.of(
                UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW),
                UserActivityLog.record(1L, 11L, 200L, ActivityType.VIEW)));
        givenVectors(List.of(
                ProductVector.of(100L, NIKE_LIKE, "nike"),
                ProductVector.of(200L, ADIDAS_LIKE, "adidas")));

        float[] userVector = newService().buildUserVector(1L).orElseThrow();

        // 같은 가중치로 하나씩 봤으니 두 방향의 중간
        assertThat(userVector[0]).isEqualTo(userVector[1]);
    }

    @Test
    void 입찰한_상품_쪽으로_취향이_기운다() {
        // 조회 1배 vs 입찰 3배. 돈이 걸린 행동이 취향을 더 강하게 드러낸다.
        givenLogs(List.of(
                UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW),
                UserActivityLog.record(1L, 11L, 200L, ActivityType.BID)));
        givenVectors(List.of(
                ProductVector.of(100L, NIKE_LIKE, "nike"),
                ProductVector.of(200L, ADIDAS_LIKE, "adidas")));

        float[] userVector = newService().buildUserVector(1L).orElseThrow();

        assertThat(CosineSimilarity.between(userVector, ADIDAS_LIKE))
                .isGreaterThan(CosineSimilarity.between(userVector, NIKE_LIKE));
    }

    @Test
    void 같은_상품을_여러_번_보면_가중치가_쌓인다() {
        // 반복해서 본 상품일수록 취향에 가깝다
        givenLogs(List.of(
                UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW),
                UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW),
                UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW),
                UserActivityLog.record(1L, 11L, 200L, ActivityType.VIEW)));
        givenVectors(List.of(
                ProductVector.of(100L, NIKE_LIKE, "nike"),
                ProductVector.of(200L, ADIDAS_LIKE, "adidas")));

        float[] userVector = newService().buildUserVector(1L).orElseThrow();

        assertThat(CosineSimilarity.between(userVector, NIKE_LIKE))
                .isGreaterThan(CosineSimilarity.between(userVector, ADIDAS_LIKE));
    }

    @Test
    void 입찰_로그의_빈_상품_id는_경매에서_찾아_채운다() {
        // 입찰 경로에 조회 쿼리를 더하지 않으려고 productId를 비워두므로, 여기서 보완해야 한다
        com.vintic.backend.auction.domain.Auction auction = mockAuction(10L, 100L);
        givenLogs(List.of(UserActivityLog.record(1L, 10L, null, ActivityType.BID)));
        when(auctionRepository.findAllById(any())).thenReturn(List.of(auction));
        givenVectors(List.of(ProductVector.of(100L, NIKE_LIKE, "nike")));

        float[] userVector = newService().buildUserVector(1L).orElseThrow();

        assertThat(CosineSimilarity.between(userVector, NIKE_LIKE)).isCloseTo(1.0, within());
    }

    @Test
    void 벡터가_없는_상품만_봤으면_취향을_만들_수_없다() {
        givenLogs(List.of(UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW)));
        givenVectors(List.of());

        assertThat(newService().buildUserVector(1L)).isEmpty();
    }

    @Test
    void 차원이_섞여도_예외없이_한쪽만_쓴다() {
        // 임베딩 모델을 바꾸면 차원이 섞일 수 있다. 평균이 의미를 잃으므로 건너뛴다.
        givenLogs(List.of(
                UserActivityLog.record(1L, 10L, 100L, ActivityType.VIEW),
                UserActivityLog.record(1L, 11L, 200L, ActivityType.VIEW)));
        givenVectors(List.of(
                ProductVector.of(100L, new float[]{1.0f, 0.0f}, "2차원"),
                ProductVector.of(200L, new float[]{0.0f, 1.0f, 0.5f}, "3차원")));

        float[] userVector = newService().buildUserVector(1L).orElseThrow();

        assertThat(userVector).hasSize(2);
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.0001);
    }

    private com.vintic.backend.auction.domain.Auction mockAuction(Long auctionId, Long productId) {
        com.vintic.backend.product.domain.Product product =
                org.mockito.Mockito.mock(com.vintic.backend.product.domain.Product.class);
        when(product.getId()).thenReturn(productId);
        com.vintic.backend.auction.domain.Auction auction =
                org.mockito.Mockito.mock(com.vintic.backend.auction.domain.Auction.class);
        when(auction.getId()).thenReturn(auctionId);
        when(auction.getProduct()).thenReturn(product);
        return auction;
    }
}
