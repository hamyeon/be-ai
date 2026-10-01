package com.vintic.backend.product.dto;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.common.util.S3UrlPresigner;
import com.vintic.backend.common.util.TimePolicy;
import com.vintic.backend.product.domain.Product;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

// 상품 등록과 첫 경매 등록이 한 트랜잭션에서 함께 처리되므로(ProductRegistrationService),
// 이 응답도 상품 정보와 그 첫 경매의 요약 정보를 함께 반환한다.
public record ProductResponse(
        Long id,
        Long sellerId,
        List<String> imageUrls,
        String brand,
        String modelName,
        String color,
        Integer size,
        String conditionGrade,
        String componentStatus,
        Integer recommendedPrice,
        Integer baseMarketPrice,
        String priceRange,
        Integer sellingPrice,
        String reason,
        String sellerDescription,
        LocalDateTime createdAt,
        Long auctionId,
        String auctionStatus,
        Long auctionStartPrice,
        Long bidIncrement,
        OffsetDateTime auctionStartAt,
        OffsetDateTime auctionEndAt
) {
    // 버킷을 public-read로 열지 않으므로(S3Config 참고), 저장된 공개 형식 URL을 응답 시점에
    // presigned GET URL로 바꿔 내보낸다 - 브라우저가 직접 열람하는 값이라 요청마다 새로 서명한다.
    private static final Duration IMAGE_URL_TTL = Duration.ofHours(24);

    public static ProductResponse from(Product product, Auction auction, S3UrlPresigner s3UrlPresigner) {
        List<String> presignedImageUrls = product.getImageUrls().stream()
                .map(url -> s3UrlPresigner.presign(url, IMAGE_URL_TTL))
                .toList();

        return new ProductResponse(
                product.getId(),
                product.getSeller().getId(),
                presignedImageUrls,
                product.getBrand(),
                product.getModel(),
                product.getColorway(),
                product.getSizeKr(),
                product.getConditionGrade(),
                product.getComponentStatus(),
                product.getRecommendedPrice(),
                product.getBaseMarketPrice(),
                product.getPriceRange(),
                product.getFinalPrice(),
                product.getReason(),
                product.getDescription(),
                product.getCreatedAt(),
                auction.getId(),
                auction.getStatus().name(),
                auction.getStartPrice(),
                auction.getBidIncrement(),
                TimePolicy.toApiTime(auction.getStartAt()),
                TimePolicy.toApiTime(auction.getEndAt())
        );
    }
}
