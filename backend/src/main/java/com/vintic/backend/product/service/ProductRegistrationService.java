package com.vintic.backend.product.service;

import com.vintic.backend.common.exception.UserNotFoundException;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.product.dto.CreateProductRequest;
import com.vintic.backend.product.dto.ProductListResponse;
import com.vintic.backend.product.dto.ProductResponse;
import com.vintic.backend.product.repository.ProductRepository;
import com.vintic.backend.recommendation.service.ProductVectorService;
import com.vintic.backend.user.domain.User;
import com.vintic.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductRegistrationService {

    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ProductVectorService productVectorService;

    public ProductRegistrationService(
            ProductRepository productRepository,
            UserRepository userRepository,
            ProductVectorService productVectorService
    ) {
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.productVectorService = productVectorService;
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request, Long sellerId) {
        User seller = userRepository.findById(sellerId)
                .orElseThrow(() -> new UserNotFoundException("존재하지 않는 사용자입니다: " + sellerId));

        Product product = new Product(
                seller,
                request.imageUrls(),
                request.brand(),
                request.modelName(),
                request.color(),
                request.size(),
                request.conditionGrade(),
                request.componentStatus(),
                request.recommendedPrice(),
                request.baseMarketPrice(),
                request.priceRange(),
                request.sellingPrice(),
                request.reason(),
                request.sellerDescription()
        );

        Product savedProduct = productRepository.save(product);
        // 추천용 벡터를 만들어 둔다. 임베딩 호출이 실패해도 refresh가 삼키므로 등록은 성공한다 -
        // 추천 품질을 위한 부가 작업이 상품 등록을 막으면 안 된다.
        productVectorService.refresh(savedProduct);
        return ProductResponse.from(savedProduct);
    }

    @Transactional(readOnly = true)
    public List<ProductListResponse> getProducts() {
        return productRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(ProductListResponse::from)
                .toList();
    }
}