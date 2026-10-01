package com.vintic.backend.product.dto;

import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.product.domain.Product;

import java.time.Duration;
import java.time.LocalDateTime;

public record ProductListResponse(
        Long id,
        String thumbnailImageUrl,
        String brand,
        String modelName,
        Integer sellingPrice,
        LocalDateTime createdAt
) {
    // 버킷을 public-read로 열지 않으므로(S3Config 참고) 목록 조회마다 새로 presign한다
    // (ProductResponse와 동일한 이유·TTL).
    private static final Duration IMAGE_URL_TTL = Duration.ofHours(24);

    public static ProductListResponse from(Product product, S3UrlPresigner s3UrlPresigner) {
        String thumbnailImageUrl = product.getImageUrls().isEmpty()
                ? null
                : s3UrlPresigner.presign(product.getImageUrls().get(0), IMAGE_URL_TTL);

        return new ProductListResponse(
                product.getId(),
                thumbnailImageUrl,
                product.getBrand(),
                product.getModel(),
                product.getFinalPrice(),
                product.getCreatedAt()
        );
    }
}