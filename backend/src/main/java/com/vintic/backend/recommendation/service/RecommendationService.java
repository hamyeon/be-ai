package com.vintic.backend.recommendation.service;

import com.vintic.backend.ai.search.embedding.CosineSimilarity;
import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.dto.RecommendationResponse;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// 경매 추천.
//
// 취향 벡터가 있으면 상품 벡터와의 코사인 유사도로 정렬하고, 없으면 Fallback으로 채운다.
// 어느 쪽이든 빈 목록이 나오지 않는 것이 이 서비스의 계약이다 - 추천 자리가 비면
// 화면이 깨진다.
@Service
@RequiredArgsConstructor
public class RecommendationService {

    // 아직 참여할 수 있는 경매만 추천한다. 끝난 경매를 추천해봐야 입찰할 수 없다.
    private static final List<AuctionStatus> OPEN_STATUSES =
            List.of(AuctionStatus.SCHEDULED, AuctionStatus.LIVE);

    // 행동이 1~2건이면 우연일 수 있어 취향이라 보기 어렵다. 3건부터 개인화를 시도한다.
    private static final long MIN_ACTIVITY_FOR_PERSONALIZATION = 3;

    private final UserActivityLogRepository activityLogRepository;
    private final UserVectorService userVectorService;
    private final ProductVectorRepository productVectorRepository;
    private final AuctionRepository auctionRepository;

    @Transactional(readOnly = true)
    public RecommendationResponse recommend(Long userId, int limit) {
        if (userId != null && activityLogRepository.countByUserId(userId) >= MIN_ACTIVITY_FOR_PERSONALIZATION) {
            Optional<float[]> userVector = userVectorService.buildUserVector(userId);
            if (userVector.isPresent()) {
                List<RecommendationResponse.RecommendedAuction> personalized =
                        byTasteSimilarity(userVector.get(), limit);
                if (!personalized.isEmpty()) {
                    return new RecommendationResponse(true,
                            "최근 보신 상품과 비슷한 경매를 골랐습니다.", personalized);
                }
            }
        }
        return fallback(limit);
    }

    private List<RecommendationResponse.RecommendedAuction> byTasteSimilarity(float[] userVector, int limit) {
        List<Auction> auctions = auctionRepository.findOpenAuctions(OPEN_STATUSES);
        if (auctions.isEmpty()) {
            return List.of();
        }

        Map<Long, float[]> vectorByProductId = loadVectors(auctions);

        return auctions.stream()
                .map(auction -> {
                    float[] productVector = vectorByProductId.get(auction.getProduct().getId());
                    // 벡터가 아직 안 만들어진 상품은 유사도를 잴 수 없다
                    if (productVector == null || productVector.length != userVector.length) {
                        return null;
                    }
                    return toItem(auction, CosineSimilarity.between(userVector, productVector));
                })
                .filter(item -> item != null)
                .sorted(Comparator.comparingDouble(
                        (RecommendationResponse.RecommendedAuction item) -> item.similarity()).reversed())
                .limit(limit)
                .toList();
    }

    // 진행 중인 경매의 벡터를 요청마다 DB에서 읽는다. 기동 시 전부 메모리에 올리는 방식과
    // 비교하면 메모리를 쓰지 않고 벡터 갱신이 즉시 반영되지만, 응답 시간이 경매 수에 비례한다.
    //
    // 로컬 측정(경매 1건당 벡터 6KB, Fallback 경로 대비 추가 시간):
    //   경매 100건 -> +13ms / 1,000건 -> +98ms / 3,000건 -> +288ms
    // 대부분이 유사도 계산이 아니라 BLOB 전송 비용이다(3,000건 = 17.6MB).
    //
    // 경매 수천 건 규모가 되면 벡터를 메모리에 캐싱해야 한다. 목록 API에 캐시를 붙이는
    // 이슈에서 함께 다루는 편이 자연스럽다.
    private Map<Long, float[]> loadVectors(List<Auction> auctions) {
        List<Long> productIds = auctions.stream()
                .map(auction -> auction.getProduct().getId())
                .distinct()
                .toList();

        return productVectorRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(ProductVector::getProductId, ProductVector::toVector));
    }

    /**
     * 취향 데이터가 없을 때. 마감 임박과 인기를 번갈아 섞는다.
     *
     * 마감 임박만 쓰면 지금 참여할 것은 보이지만 품질이 들쭉날쭉하고, 인기만 쓰면 항상 같은
     * 경매가 위에 남는다. 섞으면 "지금 참여할 것"과 "검증된 것"이 같이 보인다.
     */
    private RecommendationResponse fallback(int limit) {
        List<Auction> endingSoon = auctionRepository.findEndingSoon(
                AuctionStatus.LIVE, LocalDateTime.now(), PageRequest.of(0, limit));
        List<Auction> popular = auctionRepository.findPopular(OPEN_STATUSES, PageRequest.of(0, limit));

        // 같은 경매가 양쪽에 들어올 수 있어 순서를 지키며 중복을 제거한다
        Map<Long, Auction> merged = new LinkedHashMap<>();
        int maxSize = Math.max(endingSoon.size(), popular.size());
        for (int i = 0; i < maxSize && merged.size() < limit; i++) {
            if (i < endingSoon.size()) {
                merged.putIfAbsent(endingSoon.get(i).getId(), endingSoon.get(i));
            }
            if (merged.size() < limit && i < popular.size()) {
                merged.putIfAbsent(popular.get(i).getId(), popular.get(i));
            }
        }

        List<RecommendationResponse.RecommendedAuction> items = new ArrayList<>();
        merged.values().forEach(auction -> items.add(toItem(auction, null)));

        return new RecommendationResponse(false,
                "아직 취향을 파악할 정보가 부족해 마감 임박·인기 경매를 보여드립니다.", items);
    }

    private RecommendationResponse.RecommendedAuction toItem(Auction auction, Double similarity) {
        Product product = auction.getProduct();
        return new RecommendationResponse.RecommendedAuction(
                auction.getId(),
                product.getId(),
                product.getBrand(),
                product.getModel(),
                product.getColorway(),
                product.getSizeKr(),
                auction.getCurrentPrice(),
                auction.getEndAt() == null ? null : auction.getEndAt().toString(),
                similarity
        );
    }
}
