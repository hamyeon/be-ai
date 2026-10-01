package com.vintic.backend.recommendation.service;

import com.vintic.backend.auction.repository.AuctionRepository;
import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.ProductVector;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import com.vintic.backend.recommendation.repository.ProductVectorRepository;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// 유저가 본 상품들의 벡터를 가중 평균해서 "취향 벡터"를 만든다.
//
// 아이디어는 단순하다: 이 사람이 본 신발들의 평균이 이 사람의 취향이다.
// 다만 그냥 평균을 내면 잠깐 스쳐본 상품과 돈을 걸고 입찰한 상품이 같은 무게를 갖는다.
// 그래서 행동 종류별로 가중치를 다르게 준다.
@Service
@RequiredArgsConstructor
public class UserVectorService {

    // 행동이 취향을 드러내는 강도. 돈이 걸린 입찰이 가장 강하고, 조회는 새로고침·봇도 섞여 약하다.
    // 근거가 있는 값은 아니고 상식 수준의 출발점이다 - 데이터가 쌓이면 조정한다.
    private static final double BID_WEIGHT = 3.0;
    private static final double LIKE_WEIGHT = 2.0;
    private static final double VIEW_WEIGHT = 1.0;
    private static final double DWELL_WEIGHT = 1.0;

    // 오래된 행동까지 다 넣으면 지금 취향이 옛날 취향에 끌려간다. 최신 것만 본다.
    private static final int RECENT_ACTIVITY_LIMIT = 100;

    private final UserActivityLogRepository activityLogRepository;
    private final ProductVectorRepository productVectorRepository;
    private final AuctionRepository auctionRepository;

    /**
     * 유저 취향 벡터를 만든다. 쓸 만한 행동 기록이 없으면 비어 있는 결과를 돌려준다
     * (호출부는 이때 Cold Start Fallback으로 넘어간다).
     */
    @Transactional(readOnly = true)
    public Optional<float[]> buildUserVector(Long userId) {
        List<UserActivityLog> logs = activityLogRepository.findByUserIdOrderByCreatedAtDesc(
                userId, PageRequest.of(0, RECENT_ACTIVITY_LIMIT));
        if (logs.isEmpty()) {
            return Optional.empty();
        }

        Map<Long, Double> weightByProductId = accumulateWeights(logs);
        if (weightByProductId.isEmpty()) {
            return Optional.empty();
        }

        return weightedAverage(weightByProductId);
    }

    // 같은 상품을 여러 번 봤으면 가중치가 누적된다 - 반복해서 본 상품일수록 취향에 가깝다.
    private Map<Long, Double> accumulateWeights(List<UserActivityLog> logs) {
        Map<Long, Long> productIdByAuctionId = resolveMissingProductIds(logs);

        Map<Long, Double> weightByProductId = new HashMap<>();
        for (UserActivityLog log : logs) {
            Long productId = log.getProductId();
            if (productId == null) {
                // 입찰 로그는 경매 경로에 조회 쿼리를 더하지 않으려고 productId를 비워둔다.
                // 여기서 경매로부터 상품을 찾아 채운다.
                productId = productIdByAuctionId.get(log.getAuctionId());
            }
            if (productId == null) {
                continue;
            }
            weightByProductId.merge(productId, weightOf(log.getActivityType()), Double::sum);
        }
        return weightByProductId;
    }

    private Map<Long, Long> resolveMissingProductIds(List<UserActivityLog> logs) {
        Set<Long> auctionIds = new HashSet<>();
        for (UserActivityLog log : logs) {
            if (log.getProductId() == null && log.getAuctionId() != null) {
                auctionIds.add(log.getAuctionId());
            }
        }
        if (auctionIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> productIdByAuctionId = new HashMap<>();
        // 로그마다 조회하면 N+1이 되므로 한 번에 가져온다
        auctionRepository.findAllById(auctionIds).forEach(auction ->
                productIdByAuctionId.put(auction.getId(), auction.getProduct().getId()));
        return productIdByAuctionId;
    }

    private Optional<float[]> weightedAverage(Map<Long, Double> weightByProductId) {
        List<ProductVector> vectors = productVectorRepository.findAllById(weightByProductId.keySet());
        if (vectors.isEmpty()) {
            // 벡터가 아직 안 만들어진 상품만 봤다면 취향을 계산할 수 없다
            return Optional.empty();
        }

        float[] accumulated = null;
        double totalWeight = 0;
        for (ProductVector productVector : vectors) {
            float[] vector = productVector.toVector();
            double weight = weightByProductId.getOrDefault(productVector.getProductId(), 0.0);
            if (weight <= 0) {
                continue;
            }

            if (accumulated == null) {
                accumulated = new float[vector.length];
            } else if (accumulated.length != vector.length) {
                // 임베딩 모델을 바꾸면 차원이 섞일 수 있다. 섞인 벡터는 평균이 의미를 잃으므로 건너뛴다.
                continue;
            }

            for (int i = 0; i < vector.length; i++) {
                accumulated[i] += (float) (vector[i] * weight);
            }
            totalWeight += weight;
        }

        if (accumulated == null || totalWeight == 0) {
            return Optional.empty();
        }

        for (int i = 0; i < accumulated.length; i++) {
            accumulated[i] /= (float) totalWeight;
        }
        return Optional.of(accumulated);
    }

    private double weightOf(ActivityType type) {
        return switch (type) {
            case BID -> BID_WEIGHT;
            case LIKE -> LIKE_WEIGHT;
            case VIEW -> VIEW_WEIGHT;
            case DWELL -> DWELL_WEIGHT;
        };
    }
}
