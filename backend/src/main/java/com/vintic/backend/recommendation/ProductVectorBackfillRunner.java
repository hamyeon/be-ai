package com.vintic.backend.recommendation;

import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

// 기동 시 벡터가 없는 상품의 벡터를 채운다.
//
// 상품 등록 시점에 벡터를 만들지만, 그 기능이 생기기 전에 등록된 상품과 임베딩 호출이
// 실패했던 상품은 벡터가 비어 있다. 벡터가 없으면 추천 후보에서 조용히 빠지므로 채워둔다.
//
// 이미 만들어진 벡터는 ProductVectorService가 건너뛰므로 재기동해도 임베딩을 다시 부르지 않는다.
// 다만 상품이 많은데 벡터가 하나도 없는 상태로 기동하면 임베딩 호출이 그만큼 발생하므로,
// 기본은 꺼두고 필요할 때 켠다.
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductVectorBackfillRunner implements ApplicationRunner {

    @Value("${recommendation.vector-backfill-on-startup:false}")
    private boolean enabled;

    private final ProductRepository productRepository;
    private final ProductVectorService productVectorService;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        try {
            List<Product> products = productRepository.findAll();
            int embedded = productVectorService.refreshAll(products);
            log.info("상품 벡터 백필 완료 - 대상 {}건, 새로 임베딩 {}건", products.size(), embedded);
        } catch (RuntimeException e) {
            // 백필이 실패해도 애플리케이션은 떠야 한다. 추천이 Fallback으로 돌 뿐이다.
            log.warn("상품 벡터 백필에 실패했습니다. message={}", e.getMessage());
        }
    }
}
