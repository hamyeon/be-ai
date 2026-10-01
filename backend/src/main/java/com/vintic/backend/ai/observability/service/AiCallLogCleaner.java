package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.repository.AiCallLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

// 보관 기간이 지난 AI 호출 기록을 지운다.
//
// 응답 본문을 통째로 담기로 한 대가다. 환각 원인을 되짚으려면 원문이 필요하지만,
// 그만큼 행이 커서 무한정 쌓으면 DB를 잠식한다. 디버깅에 필요한 기간만 남긴다.
//
// 하루 한 번으로 충분하다. 기록이 하루 늦게 지워진다고 문제가 되지 않고,
// 자주 돌리면 삭제 쿼리가 쓰기 경로와 경합할 뿐이다.
@Component
@Slf4j
public class AiCallLogCleaner {

    private final AiCallLogRepository aiCallLogRepository;
    private final int retentionDays;
    private final boolean enabled;

    public AiCallLogCleaner(
            AiCallLogRepository aiCallLogRepository,
            @Value("${ai.call-log.retention-days:30}") int retentionDays,
            @Value("${ai.call-log.cleanup-enabled:true}") boolean enabled
    ) {
        this.aiCallLogRepository = aiCallLogRepository;
        this.retentionDays = retentionDays;
        this.enabled = enabled;
    }

    // 새벽 4시. 분석 요청이 가장 적은 시간대다.
    @Scheduled(cron = "${ai.call-log.cleanup-cron:0 0 4 * * *}")
    @Transactional
    public void cleanUp() {
        if (!enabled) {
            return;
        }
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        try {
            int deleted = aiCallLogRepository.deleteOlderThan(threshold);
            if (deleted > 0) {
                log.info("보관 기간이 지난 AI 호출 기록을 삭제했습니다. deleted={}, threshold={}", deleted, threshold);
            }
        } catch (RuntimeException e) {
            // 정리 실패가 서비스를 막을 이유는 없다. 다음 주기에 다시 시도한다.
            log.warn("AI 호출 기록 정리에 실패했습니다. message={}", e.getMessage());
        }
    }
}
