package com.vintic.backend.concurrency.support;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// 테스트 전용 race window 제어 장치. production 코드에는 전혀 포함되지 않는다.
// armed 상태 + 대상 auctionId가 모두 일치할 때만 sleep한다 — 그래야 동시성 실행이
// 끝난 뒤 검증용 재조회(findById)가 우연히 지연되는 것을 막을 수 있다.
// targetAuctionId/delayMillis는 pilot마다 새 Auction·새 delay 값으로 재구성해야 해서
// 생성자 고정이 아니라 매 run 시작 시 configure()로 세팅한다.
public class RaceWindowDelay {

    private final AtomicLong targetAuctionId = new AtomicLong(-1);
    private final AtomicLong delayMillis = new AtomicLong(0);
    private final AtomicBoolean armed = new AtomicBoolean(false);
    // "첫 번째 요청이 실제로 락을 잡고 delay에 들어갔다"를 테스트 스레드가 Thread.sleep으로
    // 추측하지 않고 직접 확인할 수 있게 한다(AutoBidCancelOnConcurrentManualBidMySqlIT 참고) -
    // 콜드스타트(첫 HTTP 요청의 JIT/커넥션풀 초기화 비용)가 고정된 sleep 추측보다 길면, "먼저
    // 보낸 쪽이 먼저 락을 잡는다"는 가정이 깨질 수 있다는 게 실측으로 확인됐다.
    private final AtomicReference<CountDownLatch> enteredDelay = new AtomicReference<>(new CountDownLatch(1));

    public void configure(long targetAuctionId, long delayMillis) {
        this.targetAuctionId.set(targetAuctionId);
        this.delayMillis.set(delayMillis);
        this.enteredDelay.set(new CountDownLatch(1));
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
        enteredDelay.get().countDown();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 대상 호출이 실제로 delay에 진입(= 락을 쥔 채 멈춰 섬)할 때까지 기다린다. 반환되면 그
    // 시점부터 두 번째 요청을 보내도 안전하게 "첫 번째가 이미 락을 쥐고 있다"고 보장된다.
    public boolean awaitEntry(long timeout, TimeUnit unit) throws InterruptedException {
        return enteredDelay.get().await(timeout, unit);
    }
}
