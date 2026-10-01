package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.repository.AiCallLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiCallLogCleanerTest {

    @Mock
    private AiCallLogRepository aiCallLogRepository;

    @Test
    void 보관_기간이_지난_기록을_지운다() {
        when(aiCallLogRepository.deleteOlderThan(any())).thenReturn(5);

        new AiCallLogCleaner(aiCallLogRepository, 30, true).cleanUp();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(aiCallLogRepository).deleteOlderThan(captor.capture());
        assertThat(captor.getValue()).isBefore(LocalDateTime.now().minusDays(29));
    }

    @Test
    void 꺼져있으면_삭제하지_않는다() {
        new AiCallLogCleaner(aiCallLogRepository, 30, false).cleanUp();

        verify(aiCallLogRepository, never()).deleteOlderThan(any());
    }

    @Test
    void 삭제에_실패해도_예외를_던지지_않는다() {
        // 정리 실패가 서비스를 막을 이유는 없다. 다음 주기에 다시 시도한다.
        when(aiCallLogRepository.deleteOlderThan(any())).thenThrow(new RuntimeException("락 대기 초과"));

        assertThatCode(() -> new AiCallLogCleaner(aiCallLogRepository, 30, true).cleanUp())
                .doesNotThrowAnyException();
    }
}
