package com.vintic.backend.recommendation.service;

import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 유저 행동을 기록한다.
//
// 이 로그는 추천 품질을 위한 부가 데이터지 서비스의 본래 기능이 아니다. 그래서 두 가지를 지킨다:
//   1) 실패해도 삼킨다 - 로그를 못 남겼다고 경매 조회가 실패하면 안 된다
//   2) REQUIRES_NEW로 트랜잭션을 분리한다 - 호출부가 롤백돼도 "봤다"는 사실은 남고,
//      반대로 로그 쓰기 실패가 호출부 트랜잭션을 오염시키지도 않는다
//
// 비로그인 요청(userId == null)은 기록하지 않는다. 누구의 취향인지 모르는 로그는
// 개인화에 쓸 수 없고, 인기도 집계는 로그인 사용자 것만으로도 충분하다.
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityLogService {

    private final UserActivityLogRepository activityLogRepository;

    public void recordView(Long userId, Long auctionId, Long productId) {
        record(userId, auctionId, productId, ActivityType.VIEW, null);
    }

    public void recordBid(Long userId, Long auctionId, Long productId) {
        record(userId, auctionId, productId, ActivityType.BID, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, Long auctionId, Long productId, ActivityType type, Integer dwellSeconds) {
        if (userId == null) {
            return;
        }
        try {
            activityLogRepository.save(
                    UserActivityLog.record(userId, auctionId, productId, type, dwellSeconds));
        } catch (RuntimeException e) {
            // 추천 품질이 조금 나빠질 뿐이므로 요청 자체를 실패시키지 않는다.
            log.warn("행동 로그 기록에 실패했습니다. userId={}, type={}, message={}",
                    userId, type, e.getMessage());
        }
    }
}
