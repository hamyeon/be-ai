package com.vintic.backend.ai.observability.repository;

import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface AiCallLogRepository extends JpaRepository<AiCallLog, Long> {

    // 분석 세션 하나가 부른 호출들을 순서대로. "3단계 중 어디가 느렸나"를 볼 때 쓴다.
    List<AiCallLog> findByAnalysisIdOrderByCreatedAtAsc(Long analysisId);

    // 기간별 집계. 호출 종류마다 단가가 달라 섞어 세면 의미가 없다.
    @Query("""
            select new com.vintic.backend.ai.observability.repository.AiCallStat(
                l.callType, l.promptVersion, l.modelName,
                count(l), sum(case when l.success then 0 else 1 end),
                sum(l.promptTokens), sum(l.completionTokens), avg(l.latencyMs))
            from AiCallLog l
            where l.createdAt >= :from
            group by l.callType, l.promptVersion, l.modelName
            """)
    List<AiCallStat> summarize(LocalDateTime from);

    // 보관 기간이 지난 기록 정리. 응답 본문을 통째로 담고 있어 무한정 쌓아둘 수 없다.
    @Query("delete from AiCallLog l where l.createdAt < :threshold")
    @org.springframework.data.jpa.repository.Modifying
    int deleteOlderThan(LocalDateTime threshold);
}
