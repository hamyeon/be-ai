package com.vintic.backend.recommendation.service;

import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 유저 행동을 기록한다.
//
// 이 로그는 추천 품질을 위한 부가 데이터지 서비스의 본래 기능이 아니다. 그래서 두 가지를 지킨다:
//   1) 실패해도 삼킨다 - 로그를 못 남겼다고 경매 조회나 찜하기가 실패하면 안 된다
//   2) 쓰기를 UserActivityLogWriter로 분리해 REQUIRES_NEW 트랜잭션을 따로 연다.
//      그래야 커밋 시점의 예외까지 아래 try-catch 안에서 잡힌다.
//
// 비로그인 요청(userId == null)은 기록하지 않는다. 누구의 취향인지 모르는 로그는
// 개인화에 쓸 수 없고, 인기도 집계는 로그인 사용자 것만으로도 충분하다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogService {

    private final UserActivityLogWriter writer;

    public void recordView(Long userId, Long auctionId, Long productId) {
        record(userId, auctionId, productId, ActivityType.VIEW, null);
    }

    public void recordBid(Long userId, Long auctionId, Long productId) {
        record(userId, auctionId, productId, ActivityType.BID, null);
    }

    /**
     * 찜하기. 조회보다 강한 취향 신호다(가중치 2.0).
     *
     * <p>찜 API는 멱등이라 이미 찜한 상품을 다시 눌러도 성공한다. 그때마다 로그를 쌓으면
     * 같은 상품이 가중치를 계속 얻으므로, 먼저 기존 기록을 지우고 다시 남긴다.
     */
    public void recordLike(Long userId, Long auctionId, Long productId) {
        if (userId == null) {
            return;
        }
        removeLike(userId, auctionId);
        record(userId, auctionId, productId, ActivityType.LIKE, null);
    }

    /**
     * 찜 해제. 찜은 이벤트가 아니라 상태이므로 기록을 지운다.
     *
     * <p>남겨두면 사용자가 이미 관심을 거둔 상품 쪽으로 취향 벡터가 계속 기운다.
     */
    public void removeLike(Long userId, Long auctionId) {
        if (userId == null) {
            return;
        }
        try {
            writer.deleteLike(userId, auctionId);
        } catch (Throwable e) {
            log.warn("찜 해제 로그 삭제에 실패했습니다. userId={}, auctionId={}, message={}",
                    userId, auctionId, e.getMessage());
        }
    }

    public void record(Long userId, Long auctionId, Long productId, ActivityType type, Integer dwellSeconds) {
        if (userId == null) {
            return;
        }
        try {
            writer.write(UserActivityLog.record(userId, auctionId, productId, type, dwellSeconds));
        } catch (Throwable e) {
            // 추천 품질이 조금 나빠질 뿐이므로 요청 자체를 실패시키지 않는다.
            log.warn("행동 로그 기록에 실패했습니다. userId={}, type={}, message={}",
                    userId, type, e.getMessage());
        }
    }
}
