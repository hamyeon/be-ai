package com.vintic.backend.ai.vision.service;

import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.analyze.job.processor.Sleeper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

// redis-baseline-test 프로필 전용. 실제 OpenAI 호출 없이 "Vision 처리 중" 상태를 결정적으로
// 재현하기 위한 연결 지점이다 - analyze.job.processor.FakeAnalysisProcessor(Day3)와 같은 설계로
// Sleeper를 통해 실제 대기와 테스트를 분리한다. AnalysisTaskConsumer는 이 클래스가 무엇을
// 반환하는지 모르므로 Consumer의 비즈니스 로직은 전혀 건드리지 않는다 - VisionAnalysisService
// 구현체 교체(DI)만으로 격리한다. 이 프로필에서만 @Primary인 기존 StagedVisionAnalysisService가
// 비활성화되므로(그 파일의 @Profile("!redis-baseline-test") 참고) 이 Fake가 대신 주입된다.
@Service
@Primary
@Profile("redis-baseline-test")
public class FakeVisionAnalysisService implements VisionAnalysisService {

    private final Sleeper sleeper;
    private volatile long delayMillis;

    public FakeVisionAnalysisService(
            Sleeper sleeper,
            @Value("${analysis.redis-baseline-test.vision-delay-ms:0}") long delayMillis
    ) {
        this.sleeper = sleeper;
        this.delayMillis = delayMillis;
    }

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        sleeper.sleep(delayMillis);
        return new VisionAnalysisResult(
                "fake-brand", "fake-model", "fake-color", 250,
                "fake-condition", null, Boolean.FALSE, 1.0, Boolean.FALSE,
                List.of(), List.of(), List.of(), List.of());
    }
}
