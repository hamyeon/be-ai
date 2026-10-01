package com.vintic.backend.recommendation.repository;

import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.domain.ProductVector;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductVectorRepository extends JpaRepository<ProductVector, Long> {

    // 벡터가 아직 없는 상품. 백필 대상을 찾을 때 쓴다.
    //
    // "벡터가 없는 상품"은 추천 도메인의 질문이라 Product 쪽이 아니라 여기에 둔다 -
    // product 패키지가 recommendation을 알게 되는 역방향 의존을 피한다.
    //
    // Pageable로 대상 수를 묶는 이유: 한 건당 임베딩 호출이 하나씩(유료) 나가므로,
    // 벡터 없는 상품이 아무리 쌓여 있어도 한 번에 배치 크기만큼만 처리한다.
    // 남은 것은 다음 실행이 이어서 채운다.
    @Query("""
            select p from Product p
            where not exists (select 1 from ProductVector v where v.productId = p.id)
            order by p.id asc
            """)
    List<Product> findProductsWithoutVector(Pageable pageable);
}
