package com.vintic.backend.analyze.job.processor;

// FakeAnalysisProcessor의 처리시간 시뮬레이션을 실제 대기와 분리하기 위한 seam.
// 운영/성능측정 실행에서는 ThreadSleeper(실제 Thread.sleep)를 쓰고, 단위 테스트에서는
// 이 인터페이스를 즉시 반환하는 구현으로 대체해 테스트가 느려지지 않게 한다.
public interface Sleeper {

    void sleep(long millis);
}
