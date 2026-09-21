package com.vintic.backend.analyze.job.processor;

import com.vintic.backend.common.exception.AnalysisPermanentFailureException;
import com.vintic.backend.common.exception.AnalysisTransientFailureException;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// FakeAnalysisProcessor가 설정한 결과(성공/일시 오류/영구 오류)를 결정적으로 돌려주는지,
// sleep 요청은 Sleeper로 위임되어 실제로 테스트를 느리게 만들지 않는지 확인한다.
class FakeAnalysisProcessorTest {

    // 실제로 대기하지 않고 요청받은 millis만 기록하는 테스트용 Sleeper -
    // delayMillis를 크게 설정해도 이 테스트는 즉시 끝나야 한다.
    private static class RecordingSleeper implements Sleeper {
        private final AtomicLong lastRequestedMillis = new AtomicLong(-1);

        @Override
        public void sleep(long millis) {
            lastRequestedMillis.set(millis);
        }
    }

    @Test
    void 성공으로_설정하면_payload를_돌려주고_설정한_sleep을_요청한다() {
        RecordingSleeper sleeper = new RecordingSleeper();
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(sleeper, 0);
        processor.setDelayMillis(5_000);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.SUCCESS);

        long start = System.nanoTime();
        AnalysisPayload payload = processor.process(new AnalysisInput(42L, "obj/key.jpg".getBytes()));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(payload.rawResult()).contains("42");
        assertThat(sleeper.lastRequestedMillis.get()).isEqualTo(5_000);
        assertThat(elapsedMillis).isLessThan(1_000); // 5초를 요청했지만 실제로 기다리지 않았다
    }

    @Test
    void 일시적_오류로_설정하면_AnalysisTransientFailureException을_던진다() {
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(new RecordingSleeper(), 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.TRANSIENT_ERROR);

        assertThatThrownBy(() -> processor.process(new AnalysisInput(1L, "obj/key.jpg".getBytes())))
                .isInstanceOf(AnalysisTransientFailureException.class);
    }

    @Test
    void 영구_오류로_설정하면_AnalysisPermanentFailureException을_던진다() {
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(new RecordingSleeper(), 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.PERMANENT_ERROR);

        assertThatThrownBy(() -> processor.process(new AnalysisInput(1L, "obj/key.jpg".getBytes())))
                .isInstanceOf(AnalysisPermanentFailureException.class);
    }

    @Test
    void timeout으로_설정하면_cause에_SocketTimeoutException을_담은_AnalysisTransientFailureException을_던진다() {
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(new RecordingSleeper(), 0);
        processor.setNextOutcome(FakeAnalysisProcessor.Outcome.TIMEOUT);

        assertThatThrownBy(() -> processor.process(new AnalysisInput(1L, "obj/key.jpg".getBytes())))
                .isInstanceOf(AnalysisTransientFailureException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
    }

    @Test
    void 생성자에서_주입한_기본_delay가_setter_호출_전까지_적용된다() {
        RecordingSleeper sleeper = new RecordingSleeper();
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(sleeper, 250);

        processor.process(new AnalysisInput(1L, "obj/key.jpg".getBytes()));

        assertThat(sleeper.lastRequestedMillis.get()).isEqualTo(250);
    }

    @Test
    void 초기_결과를_설정하지_않으면_SUCCESS로_동작한다() {
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(new RecordingSleeper(), 0);

        AnalysisPayload payload = processor.process(new AnalysisInput(1L, "obj/key.jpg".getBytes()));

        assertThat(payload.rawResult()).contains("1");
    }

    // Day14 AWS 장애 주입용: Worker는 web-application-type=none이라 setter를 호출할 방법이 없으므로
    // ANALYSIS_PROCESSOR_FAKE_OUTCOME 환경변수 등으로 초기 결과를 주입해야 한다.
    @Test
    void 생성자에_주입한_초기_결과가_TIMEOUT이면_setter_없이도_timeout을_던진다() {
        FakeAnalysisProcessor processor = new FakeAnalysisProcessor(
                new RecordingSleeper(), 0, FakeAnalysisProcessor.Outcome.TIMEOUT);

        assertThatThrownBy(() -> processor.process(new AnalysisInput(1L, "obj/key.jpg".getBytes())))
                .isInstanceOf(AnalysisTransientFailureException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
    }
}
