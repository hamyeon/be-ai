package com.vintic.backend.recommendation.service;

import com.vintic.backend.ai.search.embedding.EmbeddingClient;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductVectorServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private ProductVectorRepository productVectorRepository;

    private ProductVectorService newService() {
        return new ProductVectorService(embeddingClient, productVectorRepository);
    }

    private Product product(Long id, String brand, String model) {
        Product product = new Product(null, List.of("https://example.com/a.jpg"), brand, model,
                "Panda", 270, "A", "FULL", 180000, 180000, null, null, null, null);
        ReflectionTestUtils.setField(product, "id", id);
        return product;
    }

    @Test
    void 저장된_벡터가_없으면_임베딩을_호출한다() {
        when(productVectorRepository.findById(1L)).thenReturn(Optional.empty());
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(productVectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(newService().refresh(product(1L, "Nike", "Dunk Low"))).isNotNull();

        verify(embeddingClient, times(1)).embed(anyString());
    }

    @Test
    void 같은_내용의_벡터가_있으면_임베딩을_다시_부르지_않는다() {
        // 임베딩은 유료라 상품 정보가 그대로면 재사용해야 한다
        Product target = product(1L, "Nike", "Dunk Low");
        String sourceText = ProductVectorText.of(target);
        when(productVectorRepository.findById(1L))
                .thenReturn(Optional.of(ProductVector.of(1L, new float[]{0.1f}, sourceText)));

        newService().refresh(target);

        verify(embeddingClient, never()).embed(anyString());
        verify(productVectorRepository, never()).save(any());
    }

    @Test
    void 상품_정보가_바뀌면_벡터를_다시_만든다() {
        Product changed = product(1L, "Nike", "Dunk High");
        when(productVectorRepository.findById(1L))
                .thenReturn(Optional.of(ProductVector.of(1L, new float[]{0.1f}, "Nike Dunk Low Panda 270mm")));
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.3f});
        when(productVectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        newService().refresh(changed);

        verify(embeddingClient, times(1)).embed(anyString());
    }

    @Test
    void 임베딩_실패는_예외를_던지지_않고_null을_돌려준다() {
        // 추천은 없어도 서비스가 돌아가야 한다
        when(productVectorRepository.findById(1L)).thenReturn(Optional.empty());
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("OpenAI 오류"));

        Product target = product(1L, "Nike", "Dunk Low");
        assertThatCode(() -> assertThat(newService().refresh(target)).isNull())
                .doesNotThrowAnyException();
    }

    @Test
    void 벡터_조회가_실패해도_예외가_새어나가지_않는다() {
        // 이 메서드는 상품 등록 경로에서 불린다. 여기서 예외가 새면 추천용 부가 작업 때문에
        // 상품 등록이 실패한다.
        when(productVectorRepository.findById(1L)).thenThrow(new RuntimeException("DB 연결 실패"));

        Product target = product(1L, "Nike", "Dunk Low");
        assertThatCode(() -> assertThat(newService().refresh(target)).isNull())
                .doesNotThrowAnyException();
    }

    @Test
    void 임베딩할_내용이_없으면_호출하지_않는다() {
        Product empty = product(1L, null, null);
        ReflectionTestUtils.setField(empty, "colorway", null);
        ReflectionTestUtils.setField(empty, "sizeKr", null);
        ReflectionTestUtils.setField(empty, "conditionGrade", null);
        ReflectionTestUtils.setField(empty, "componentStatus", null);
        ReflectionTestUtils.setField(empty, "recommendedPrice", null);

        assertThat(newService().refresh(empty)).isNull();

        verify(embeddingClient, never()).embed(anyString());
    }

    @Test
    void 여러_상품_중_하나가_실패해도_나머지를_계속_처리한다() {
        when(productVectorRepository.findById(any())).thenReturn(Optional.empty());
        when(embeddingClient.embed(anyString()))
                .thenThrow(new RuntimeException("일시 오류"))
                .thenReturn(new float[]{0.2f});
        when(productVectorRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        int embedded = newService().refreshAll(
                List.of(product(1L, "Nike", "Dunk Low"), product(2L, "Adidas", "Samba")));

        assertThat(embedded).isEqualTo(1);
        verify(embeddingClient, times(2)).embed(anyString());
    }
}
