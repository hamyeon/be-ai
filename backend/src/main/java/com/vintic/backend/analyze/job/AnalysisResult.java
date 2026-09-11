package com.vintic.backend.analyze.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDateTime;

// Day4 SQS 경로 검증에 필요한 최소 임시 결과 저장소. AnalysisProcessor.process()의 임시 반환값인
// AnalysisPayload.rawResult()를 그대로 저장한다 - 최종 Vision/Pricing 결과 스키마(#86)는 이 클래스가
// 확정하지 않으며, 스프린트 중 별도로 정해진다(ADR-17 참고).
//
// analysis_id UNIQUE는 "성공 완료는 analysisId당 정확히 1건"이라는 불변식을 애플리케이션 로직이
// 아니라 DB 제약으로 강제하기 위함이다 - 동시 완료 경쟁에서 두 번째 INSERT가 반드시 실패해야 한다.
@Entity
@Table(
        name = "analysis_result",
        uniqueConstraints = @UniqueConstraint(name = "uk_analysis_result_analysis_id", columnNames = "analysis_id")
)
@Getter
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Lob
    @Column(name = "raw_result", nullable = false, columnDefinition = "LONGTEXT")
    private String rawResult;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected AnalysisResult() {
    }

    public static AnalysisResult create(Long analysisId, String rawResult) {
        AnalysisResult result = new AnalysisResult();
        result.analysisId = analysisId;
        result.rawResult = rawResult;
        result.createdAt = LocalDateTime.now();
        return result;
    }
}
