package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AiCallLoggerTest {

    @Mock
    private AiCallLogWriter aiCallLogWriter;

    private AiCallLog sample() {
        return AiCallLog.builder(AiCallType.VISION, "gpt-4o").latencyMs(100).build();
    }

    @Test
    void 기록을_저장한다() {
        new AiCallLogger(aiCallLogWriter).record(sample());

        verify(aiCallLogWriter).write(any());
    }

    @Test
    void 저장이_실패해도_예외를_던지지_않는다() {
        // 관측용 부가 작업이 AI 호출을 실패시키면 안 된다.
        doThrow(new RuntimeException("DB 연결 실패")).when(aiCallLogWriter).write(any());

        assertThatCode(() -> new AiCallLogger(aiCallLogWriter).record(sample()))
                .doesNotThrowAnyException();
    }

    @Test
    void 트랜잭션_커밋_실패도_막는다() {
        // 쓰기를 별도 빈으로 분리한 이유. 프록시 경계가 write()에 있어야 커밋 시점의
        // 예외까지 이 자리에서 잡힌다. 같은 클래스 안에 두면 커밋 예외가 호출부로 새어 나간다.
        doThrow(new org.springframework.transaction.TransactionSystemException("커밋 실패"))
                .when(aiCallLogWriter).write(any());

        assertThatCode(() -> new AiCallLogger(aiCallLogWriter).record(sample()))
                .doesNotThrowAnyException();
    }

    @Test
    void 저장이_Error를_던져도_예외가_새어나가지_않는다() {
        // RuntimeException만 잡으면 컬럼 길이 초과 같은 상황에서 나오는 Error 계열을 놓친다.
        doThrow(new StackOverflowError("한계 상황")).when(aiCallLogWriter).write(any());

        assertThatCode(() -> new AiCallLogger(aiCallLogWriter).record(sample()))
                .doesNotThrowAnyException();
    }
}
