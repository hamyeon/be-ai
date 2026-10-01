package com.vintic.backend.recommendation;

import com.vintic.backend.common.dto.ApiResponse;
import com.vintic.backend.recommendation.dto.RecommendationResponse;
import com.vintic.backend.recommendation.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    // 로그인하지 않아도 호출할 수 있다. 그때는 취향 데이터가 없으므로 Fallback이 나간다.
    // #75-5A: X-User-Id를 직접 읽던 경로를 제거했다 - dev/prod identity source는
    // JwtAuthenticationFilter가, local은 MockAuthInterceptor가 채우는 currentUserId
    // request attribute만 신뢰한다(AuctionController의 anonymous GET 3개와 동일 패턴).
    @GetMapping("/auctions")
    public ResponseEntity<ApiResponse<RecommendationResponse>> recommendAuctions(
            @RequestAttribute(value = "currentUserId", required = false) Long userId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        RecommendationResponse response = recommendationService.recommend(userId, normalize(limit));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 후보 전체를 메모리에서 정렬하므로 limit이 커도 이득이 없고 응답만 무거워진다.
    private int normalize(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
