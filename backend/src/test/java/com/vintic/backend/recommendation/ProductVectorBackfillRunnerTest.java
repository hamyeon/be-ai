package com.vintic.backend.recommendation;

import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductVectorBackfillRunnerTest {

    @Mock
    private ProductVectorRepository productVectorRepository;

    @Mock
    private ProductVectorService productVectorService;

    private ProductVectorBackfillRunner runner(boolean onStartup, boolean scheduled, int batchSize) {
        return new ProductVectorBackfillRunner(
                productVectorRepository, productVectorService, onStartup, scheduled, batchSize);
    }

    @Test
    void 기동_시_벡터_없는_상품만_배치_크기만큼_채운다() {
        // 예전 방식은 findAll로 전체 상품을 읽은 뒤 건별로 확인했다.
        // 상품이 늘면 기동마다 전체를 메모리에 올리게 되므로 대상만 조회한다.
        Product target = mock(Product.class);
        when(productVectorRepository.findProductsWithoutVector(any(Pageable.class)))
                .thenReturn(List.of(target));

        runner(true, false, 50).run(null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productVectorRepository).findProductsWithoutVector(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
        verify(productVectorService).refreshAll(List.of(target));
    }

    @Test
    void 꺼져_있으면_아무_것도_하지_않는다() {
        runner(false, false, 50).run(null);
        runner(false, false, 50).backfillPeriodically();

        verify(productVectorRepository, never()).findProductsWithoutVector(any());
        verify(productVectorService, never()).refreshAll(anyList());
    }

    @Test
    void 대상이_없으면_임베딩을_부르지_않는다() {
        when(productVectorRepository.findProductsWithoutVector(any(Pageable.class)))
                .thenReturn(List.of());

        runner(true, true, 50).run(null);

        verify(productVectorService, never()).refreshAll(anyList());
    }

    @Test
    void 주기_배치도_같은_경로로_채운다() {
        Product target = mock(Product.class);
        when(productVectorRepository.findProductsWithoutVector(any(Pageable.class)))
                .thenReturn(List.of(target));

        runner(false, true, 50).backfillPeriodically();

        verify(productVectorService).refreshAll(List.of(target));
    }

    @Test
    void 백필이_실패해도_예외를_던지지_않는다() {
        // 기동 경로에서 예외가 새면 애플리케이션이 못 뜬다. 추천이 해당 상품을 뒤로 미룰 뿐이다.
        when(productVectorRepository.findProductsWithoutVector(any(Pageable.class)))
                .thenThrow(new RuntimeException("DB 연결 실패"));

        assertThatCode(() -> runner(true, true, 50).run(null)).doesNotThrowAnyException();
        assertThatCode(() -> runner(true, true, 50).backfillPeriodically()).doesNotThrowAnyException();
    }
}
