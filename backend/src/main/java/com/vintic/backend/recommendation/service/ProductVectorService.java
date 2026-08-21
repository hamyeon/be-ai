package com.vintic.backend.recommendation.service;

import com.vintic.backend.ai.search.embedding.EmbeddingClient;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// 상품 벡터를 만들고 저장한다.
//
// 임베딩 호출은 유료라 "이미 만든 건 다시 만들지 않는다"가 핵심이다. 상품 정보가 그대로면
// 입력 텍스트도 그대로이므로, 저장된 sourceText와 비교해 달라진 것만 다시 부른다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductVectorService {

    private final EmbeddingClient embeddingClient;
    private final ProductVectorRepository productVectorRepository;

    /**
     * 상품 벡터를 최신 상태로 만든다. 이미 같은 텍스트로 만든 벡터가 있으면 임베딩을 호출하지 않는다.
     * 실패하면 null을 돌려주고 예외를 던지지 않는다 - 추천은 없어도 서비스가 돌아가야 한다.
     */
    @Transactional
    public ProductVector refresh(Product product) {
        String sourceText = ProductVectorText.of(product);
        if (sourceText.isBlank()) {
            // 브랜드/모델이 전부 비어 있으면 임베딩할 내용이 없다
            return null;
        }

        Optional<ProductVector> existing = productVectorRepository.findById(product.getId());
        if (existing.isPresent() && !existing.get().isStale(sourceText)) {
            return existing.get();
        }

        try {
            float[] vector = embeddingClient.embed(sourceText);
            return productVectorRepository.save(ProductVector.of(product.getId(), vector, sourceText));
        } catch (RuntimeException e) {
            log.warn("상품 벡터 생성에 실패했습니다. productId={}, message={}", product.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 여러 상품의 벡터를 채운다. 한 건이 실패해도 나머지는 계속 처리한다.
     *
     * @return 이번에 새로 임베딩을 호출한 건수
     */
    @Transactional
    public int refreshAll(List<Product> products) {
        int embedded = 0;
        for (Product product : products) {
            String sourceText = ProductVectorText.of(product);
            boolean needsEmbedding = productVectorRepository.findById(product.getId())
                    .map(vector -> vector.isStale(sourceText))
                    .orElse(true);

            if (refresh(product) != null && needsEmbedding) {
                embedded++;
            }
        }
        return embedded;
    }

    @Transactional(readOnly = true)
    public List<ProductVector> findAll() {
        return productVectorRepository.findAll();
    }
}
