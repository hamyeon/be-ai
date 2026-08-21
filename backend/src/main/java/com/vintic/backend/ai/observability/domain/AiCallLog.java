package com.vintic.backend.ai.observability.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

// AI API 호출 한 건의 기록.
//
// 지금도 promptVersion, modelName, latencyMs, 토큰 사용량이 전부 log.info로 찍히고 있지만
// 애플리케이션 로그는 재시작하면 사라진다. "저 상품 분석이 왜 저렇게 나왔지"를 나중에
// 되짚으려면 값이 남아 있어야 한다.
//
// 기록 단위를 세션이 아니라 호출로 잡았다. Vision 분석 한 건은 3단계로 API를 세 번 부르는데,
// ProductAnalysisSession에는 최종 결과 하나만 남아서 어느 단계가 느렸는지, 어느 단계에서
// 스키마를 어겼는지 구분할 수 없다.
@Entity
@Table(
        name = "ai_call_logs",
        indexes = {
                // "이 분석 세션의 호출들을 순서대로" - 가장 자주 하게 될 조회
                @Index(name = "idx_ai_call_analysis", columnList = "analysis_id, created_at"),
                // 기간별 토큰/실패율 집계
                @Index(name = "idx_ai_call_type_created", columnList = "call_type, created_at"),
                // 프롬프트 버전 전후 비교
                @Index(name = "idx_ai_call_prompt_version", columnList = "prompt_version, created_at")
        }
)
public class AiCallLog {

    // 요청/응답 본문 저장 상한. Vision 응답은 보통 수 KB지만, 비정상 응답이 들어와도
    // 행 하나가 DB를 압박하지 않도록 잘라 담는다.
    private static final int MAX_BODY_LENGTH = 20_000;

    private static final int MAX_FAILURE_MESSAGE_LENGTH = 1_000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, length = 20)
    private AiCallType callType;

    // Vision 3단계 중 어느 단계인지(silhouette/label/condition). 임베딩은 단계가 없어 null이다.
    @Column(name = "stage", length = 50)
    private String stage;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    // 프롬프트를 바꿨을 때 전후를 비교하려면 어느 버전으로 부른 결과인지가 남아야 한다.
    // 임베딩은 프롬프트가 없어 null이다.
    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    // ProductAnalysisSession.id. 세션 하나에 호출 여러 건이 달린다.
    // 상품 등록 시 임베딩처럼 세션 밖에서 부르는 호출은 null이다.
    @Column(name = "analysis_id")
    private Long analysisId;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type", length = 30)
    private AiCallFailureType failureType;

    @Column(name = "failure_message", length = MAX_FAILURE_MESSAGE_LENGTH)
    private String failureMessage;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    // 무엇을 보냈는지. API 키가 담긴 헤더는 절대 넣지 않는다(AiCallRequestSummary 참고).
    @Column(name = "request_summary", columnDefinition = "LONGTEXT")
    private String requestSummary;

    // 응답 본문 원문. 환각의 원인을 되짚으려면 결과 DTO가 아니라 모델이 실제로 뱉은 문자열이 필요하다.
    @Column(name = "response_body", columnDefinition = "LONGTEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected AiCallLog() {
    }

    public static Builder builder(AiCallType callType, String modelName) {
        return new Builder(callType, modelName);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(생략, 원본 " + value.length() + "자)";
    }

    // 필드가 열 개를 넘어 생성자로는 어느 인자가 무엇인지 알아볼 수 없다.
    public static final class Builder {

        private final AiCallLog target = new AiCallLog();

        private Builder(AiCallType callType, String modelName) {
            if (callType == null) {
                throw new IllegalArgumentException("호출 종류는 필수입니다.");
            }
            if (modelName == null || modelName.isBlank()) {
                throw new IllegalArgumentException("모델명은 필수입니다.");
            }
            target.callType = callType;
            target.modelName = modelName;
            target.success = true;
        }

        public Builder stage(String stage) {
            target.stage = stage;
            return this;
        }

        public Builder promptVersion(String promptVersion) {
            target.promptVersion = promptVersion;
            return this;
        }

        public Builder analysisId(Long analysisId) {
            target.analysisId = analysisId;
            return this;
        }

        public Builder latencyMs(long latencyMs) {
            target.latencyMs = latencyMs;
            return this;
        }

        public Builder tokens(int promptTokens, int completionTokens) {
            target.promptTokens = promptTokens;
            target.completionTokens = completionTokens;
            return this;
        }

        public Builder requestSummary(String requestSummary) {
            target.requestSummary = truncate(requestSummary, MAX_BODY_LENGTH);
            return this;
        }

        public Builder responseBody(String responseBody) {
            target.responseBody = truncate(responseBody, MAX_BODY_LENGTH);
            return this;
        }

        public Builder failure(AiCallFailureType failureType, String failureMessage) {
            target.success = false;
            target.failureType = failureType;
            target.failureMessage = truncate(failureMessage, MAX_FAILURE_MESSAGE_LENGTH);
            return this;
        }

        public AiCallLog build() {
            target.createdAt = LocalDateTime.now();
            return target;
        }
    }

    public Long getId() {
        return id;
    }

    public AiCallType getCallType() {
        return callType;
    }

    public String getStage() {
        return stage;
    }

    public String getModelName() {
        return modelName;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public Long getAnalysisId() {
        return analysisId;
    }

    public boolean isSuccess() {
        return success;
    }

    public AiCallFailureType getFailureType() {
        return failureType;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public String getRequestSummary() {
        return requestSummary;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
