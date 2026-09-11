package com.vintic.backend.analyze.job.processor;

import com.vintic.backend.common.exception.AnalysisPermanentFailureException;
import com.vintic.backend.common.exception.AnalysisTransientFailureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// ADR-17의 Fake 구현. 처리시간(sleep)과 다음 결과를 결정적으로 주입할 수 있다 -
// 대량 성능 측정(Fake sleep으로 부하 시뮬레이션)과 오류 주입 테스트(일시적/영구 오류)에 쓴다.
//
// delayMillis/nextOutcome은 setter로 실행 중에도 바꿀 수 있다 - 테스트가 시나리오별로
// 결정적으로 다음 호출 결과를 지정할 수 있어야 하기 때문이다. 기본 delayMillis는
// analysis.processor.fake.delay-ms 설정으로 주입하고(성능 측정용), 개별 테스트는 setter로
// 0으로 두거나 원하는 값으로 덮어쓴다. Sleeper를 통해 실제 대기와 분리했으므로, 테스트에서
// FakeSleeper(즉시 반환)를 주입하면 delayMillis를 크게 설정해도 테스트가 느려지지 않는다.
@Component
public class FakeAnalysisProcessor implements AnalysisProcessor {

    public enum Outcome {
        SUCCESS,
        TRANSIENT_ERROR,
        PERMANENT_ERROR
    }

    private final Sleeper sleeper;
    private volatile long delayMillis;
    private volatile Outcome nextOutcome = Outcome.SUCCESS;

    public FakeAnalysisProcessor(
            Sleeper sleeper,
            @Value("${analysis.processor.fake.delay-ms:0}") long delayMillis
    ) {
        this.sleeper = sleeper;
        this.delayMillis = delayMillis;
    }

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public void setNextOutcome(Outcome nextOutcome) {
        this.nextOutcome = nextOutcome;
    }

    @Override
    public AnalysisPayload process(AnalysisInput input) {
        sleeper.sleep(delayMillis);

        return switch (nextOutcome) {
            case SUCCESS -> new AnalysisPayload("fake-result-" + input.analysisId());
            case TRANSIENT_ERROR -> throw new AnalysisTransientFailureException(
                    "Fake 프로세서에 설정된 일시적 오류입니다. analysisId=" + input.analysisId());
            case PERMANENT_ERROR -> throw new AnalysisPermanentFailureException(
                    "Fake 프로세서에 설정된 영구 오류입니다. analysisId=" + input.analysisId());
        };
    }
}
