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
    private final FallbackRecommendationProvider fallbackProvider;

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

        List<RecommendationResponse.RecommendedAuction> ranked = new ArrayList<>();
        List<Auction> unranked = new ArrayList<>();
        for (Auction auction : auctions) {
            float[] productVector = vectorByProductId.get(auction.getProduct().getId());
            if (productVector == null || productVector.length != userVector.length) {
                // 벡터가 아직 안 만들어진 상품은 유사도를 잴 수 없다.
                // 그렇다고 버리면 안 된다 - 유사도를 못 매기는 것과 보여줄 수 없는 것은 다르다.
                // 버리면 같은 경매가 Fallback 사용자에게는 보이고 개인화 사용자에게는 안 보여서,
                // 서비스를 열심히 쓴 사용자일수록 매물을 적게 보는 역전이 생긴다.
                unranked.add(auction);
                continue;
            }
            ranked.add(toItem(auction, CosineSimilarity.between(userVector, productVector)));
        }

        // 취향 순으로 매긴 것이 하나도 없으면 개인화라 부를 수 없다 - Fallback으로 보낸다.
        // Fallback은 마감 임박과 인기를 섞어주므로 순위 근거 없는 나열보다 낫다.
        if (ranked.isEmpty()) {
            return List.of();
        }

        ranked.sort(Comparator.comparingDouble(
                (RecommendationResponse.RecommendedAuction item) -> item.similarity()).reversed());

        // 순위를 못 매긴 경매는 유사도 순 뒤에 마감 임박 순으로 붙인다.
        // similarity는 null로 둔다 - 평균값 같은 걸 채우면 측정하지 않은 숫자를 측정한
        // 것처럼 내보내는 셈이다. null 처리는 Fallback과 같아 프론트가 이미 다루고 있다.
        unranked.sort(Comparator.comparing(Auction::getEndAt,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<RecommendationResponse.RecommendedAuction> items = new ArrayList<>(ranked);
        for (Auction auction : unranked) {
            items.add(toItem(auction, null));
        }
        return items.size() <= limit ? items : items.subList(0, limit);
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

    // Fallback은 FallbackRecommendationProvider가 만든다. 결과가 요청자와 무관해
    // 캐싱 대상이고, 캐시 프록시를 지나려면 다른 빈이어야 한다.
    private RecommendationResponse fallback(int limit) {
        return fallbackProvider.recommend(limit);
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
