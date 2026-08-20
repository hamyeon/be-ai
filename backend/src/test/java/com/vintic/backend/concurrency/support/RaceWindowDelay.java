package com.vintic.backend.concurrency.support;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

// 테스트 전용 race window 제어 장치. production 코드에는 전혀 포함되지 않는다.
// armed 상태 + 대상 auctionId가 모두 일치할 때만 sleep한다 — 그래야 동시성 실행이
// 끝난 뒤 검증용 재조회(findById)가 우연히 지연되는 것을 막을 수 있다.
// targetAuctionId/delayMillis는 pilot마다 새 Auction·새 delay 값으로 재구성해야 해서
// 생성자 고정이 아니라 매 run 시작 시 configure()로 세팅한다.
public class RaceWindowDelay {

    private final AtomicLong targetAuctionId = new AtomicLong(-1);
    private final AtomicLong delayMillis = new AtomicLong(0);
    private final AtomicBoolean armed = new AtomicBoolean(false);

    public void configure(long targetAuctionId, long delayMillis) {
        this.targetAuctionId.set(targetAuctionId);
        this.delayMillis.set(delayMillis);
    }

    public void arm() {
        armed.set(true);
    }

    public void disarm() {
        armed.set(false);
    }

    public void applyIfTarget(Long auctionId) {
        long delay = delayMillis.get();
        if (delay <= 0 || !armed.get() || auctionId == null || auctionId != targetAuctionId.get()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
