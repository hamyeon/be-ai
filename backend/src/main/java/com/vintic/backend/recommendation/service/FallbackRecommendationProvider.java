package com.vintic.backend.recommendation.service;

import com.vintic.backend.auction.domain.Auction;
import com.vintic.backend.auction.domain.AuctionStatus;
import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.config.CacheConfig;
import com.vintic.backend.product.domain.Product;
import com.vintic.backend.recommendation.dto.RecommendationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 취향 데이터가 없을 때 보여줄 목록(마감 임박 + 인기)을 만든다.
//
// RecommendationService에서 분리한 이유는 두 가지다.
//
// 1) 캐시가 실제로 걸리게 하려면 프록시를 지나야 한다. 같은 클래스 안에서 private
//    메서드를 부르면 @Cacheable이 아무 일도 하지 않는다. 트랜잭션과 같은 함정이다.
//
// 2) 이 결과만 캐싱 대상이다. 개인화 경로는 유저마다 결과가 달라 캐시 적중률이 낮고,
//    캐시 키에 userId가 들어가면 사용자 수만큼 항목이 늘어난다. 반면 이쪽은 비로그인과
//    신규 유저 전부가 같은 값을 받으므로 한 번 계산해 공유하는 편이 이득이 크다.
//
// 캐시가 낡는 시점은 입찰이다. 입찰이 들어오면 현재가와 인기 순위가 함께 바뀐다.
// ManualBidService에서 @CacheEvict로 비운다.
@Component
@RequiredArgsConstructor
public class FallbackRecommendationProvider {

    private static final List<AuctionStatus> OPEN_STATUSES =
            List.of(AuctionStatus.SCHEDULED, AuctionStatus.LIVE);

    private final AuctionRepository auctionRepository;

    /**
     * 마감 임박과 인기를 번갈아 섞는다.
     *
     * <p>마감 임박만 쓰면 지금 참여할 것은 보이지만 품질이 들쭉날쭉하고, 인기만 쓰면 항상
     * 같은 경매가 위에 남는다. 섞으면 "지금 참여할 것"과 "검증된 것"이 같이 보인다.
     */
    // 캐시 키는 limit만 쓴다. 이 결과는 요청자와 무관하고 개수에만 좌우된다.
    //
    // 주의: "마감 임박"은 시간에 따라 순서가 바뀌므로 TTL을 짧게 둬야 한다. 길게 잡으면
    // 이미 끝난 경매가 목록에 남는다. TTL은 CacheConfig에서 별도로 설정한다.
    @Cacheable(cacheNames = CacheConfig.RECOMMENDATION_FALLBACK_CACHE, key = "#limit")
    @Transactional(readOnly = true)
    public RecommendationResponse recommend(int limit) {
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
        merged.values().forEach(auction -> items.add(toItem(auction)));

        return new RecommendationResponse(false,
                "아직 취향을 파악할 정보가 부족해 마감 임박·인기 경매를 보여드립니다.", items);
    }

    private RecommendationResponse.RecommendedAuction toItem(Auction auction) {
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
                // Fallback에는 유사도가 없다. 프론트가 개인화 결과와 구분할 수 있어야 한다.
                null
        );
    }
}
