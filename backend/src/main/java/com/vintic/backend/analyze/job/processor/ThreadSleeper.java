package com.vintic.backend.analyze.job.processor;

import org.springframework.stereotype.Component;

// Sleeper의 운영용 구현. 실제로 Thread.sleep으로 대기한다.
@Component
public class ThreadSleeper implements Sleeper {

    @Override
    public void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sleeper 대기 중 인터럽트되었습니다.", e);
        }
    }
}
