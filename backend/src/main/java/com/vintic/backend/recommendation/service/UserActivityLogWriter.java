package com.vintic.backend.recommendation.service;

import com.vintic.backend.recommendation.domain.ActivityType;
import com.vintic.backend.recommendation.domain.UserActivityLog;
import com.vintic.backend.recommendation.repository.UserActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 행동 로그 쓰기만 담당한다. 예외를 삼키지 않고 그대로 던진다 - 삼키는 건 ActivityLogService의 몫이다.
//
// ActivityLogService에서 분리한 이유는 트랜잭션 프록시의 위치 때문이다. @Transactional을 붙인
// 메서드는 프록시가 감싸고, 커밋은 메서드가 반환된 뒤에 일어난다. 그래서 같은 메서드 안에서
// try-catch를 해도 커밋 시점의 예외는 잡히지 않고 호출부로 튀어나간다.
//
// 쓰기를 별도 빈으로 빼면 프록시 경계가 이 클래스에 생기고, ActivityLogService 입장에서는
// 이 메서드들이 예외를 던지는 평범한 호출이 된다. 그제서야 try-catch가 커밋 실패까지 덮는다.
// (같은 문제를 #49 상품 벡터, #51 AI 호출 로그에서 겪었다 - docs/troubleshooting.md 2번)
@Component
@RequiredArgsConstructor
public class UserActivityLogWriter {

    private final UserActivityLogRepository activityLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UserActivityLog log) {
        // save만 하면 INSERT가 커밋 시점에 실행돼 호출부의 catch를 빠져나간다.
        activityLogRepository.saveAndFlush(log);
    }

    /**
     * 찜을 해제했을 때 그 찜 기록을 지운다.
     *
     * <p>찜은 이벤트가 아니라 상태다. 해제했는데 로그가 남아 있으면 취향 벡터가 계속
     * 가중치 2.0을 얹어, 사용자가 이미 관심을 거둔 상품 쪽으로 추천이 기운다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteLike(Long userId, Long auctionId) {
        activityLogRepository.deleteByUserIdAndAuctionIdAndActivityType(
                userId, auctionId, ActivityType.LIKE);
    }
}
