package com.vintic.backend.analyze.domain;

import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// 상품 등록 전 Vision -> (사용자 확인) -> Pricing으로 이어지는 AI 분석 진행 상태를 추적하는 세션.
// Vision/Pricing 각 단계의 성공/실패 결과를 저장해 장애 추적과 두 API 요청 간 연결(analysisId)을 가능하게 한다.
@Entity
@Table(name = "product_analysis_session")
@Getter
public class ProductAnalysisSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status;

    @ElementCollection
    @CollectionTable(
            name = "product_analysis_session_image_urls",
            joinColumns = @JoinColumn(name = "session_id")
    )
    @Column(name = "image_url", length = 1000)
    private List<String> imageUrls = new ArrayList<>();

    @Lob
    @Column(name = "vision_result_json", columnDefinition = "LONGTEXT")
    private String visionResultJson;

    // #106: 3단계 분석 도중의 잠정 결과(VisionProgress). VISION_PROCESSING 동안만 채워지고 끝나면 비운다.
    @Lob
    @Column(name = "vision_progress_json", columnDefinition = "LONGTEXT")
    private String visionProgressJson;

    // 판매자가 Vision 결과를 확인/수정해 Pricing 요청에 실제로 전달한 최종 입력값
    @Lob
    @Column(name = "confirmed_input_json", columnDefinition = "LONGTEXT")
    private String confirmedInputJson;

    @Lob
    @Column(name = "pricing_result_json", columnDefinition = "LONGTEXT")
    private String pricingResultJson;

    @Enumerated(EnumType.STRING)
    private AnalysisFailureStage failureStage;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    protected ProductAnalysisSession() {
    }

    public static ProductAnalysisSession create() {
        ProductAnalysisSession session = new ProductAnalysisSession();
        session.status = AnalysisStatus.CREATED;
        session.startedAt = LocalDateTime.now();
        return session;
    }

    public void markImageUploaded(List<String> imageUrls) {
        this.imageUrls = new ArrayList<>(imageUrls);
        this.status = AnalysisStatus.IMAGE_UPLOADED;
    }

    public void failImageUpload(String message) {
        this.status = AnalysisStatus.IMAGE_UPLOAD_FAILED;
        this.failureStage = AnalysisFailureStage.IMAGE_UPLOAD;
        this.failureMessage = message;
    }

    public void markQueued() {
        this.status = AnalysisStatus.QUEUED;
    }

    public void failQueueing(String message) {
        this.status = AnalysisStatus.QUEUE_FAILED;
        this.failureStage = AnalysisFailureStage.QUEUE;
        this.failureMessage = message;
    }

    // Consumer가 큐 메시지를 처리할 때 호출한다. QUEUED 상태가 아니면 이미 처리됐거나(중복 전달)
    // 아직 큐 적재 전인 것이므로, 같은 Vision 분석이 두 번 실행되지 않도록 여기서 막는다.
    public void startVisionProcessing() {
        if (status != AnalysisStatus.QUEUED) {
            throw new InvalidAnalysisStatusException(
                    "Vision 분석을 시작할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.VISION_PROCESSING;
    }

    // 분석 도중 끝난 단계까지의 잠정 결과를 남긴다(#106). 분석 중일 때만 받는다 - 늦게 도착한 진행 기록이
    // 이미 끝났거나 실패로 정리된 세션에 섞이면 안 된다. 받았으면 true.
    public boolean recordVisionProgress(String visionProgressJson) {
        if (status != AnalysisStatus.VISION_PROCESSING) {
            return false;
        }
        this.visionProgressJson = visionProgressJson;
        return true;
    }

    public void completeVision(String visionResultJson) {
        this.visionResultJson = visionResultJson;
        this.visionProgressJson = null; // 최종 결과가 생기면 잠정 결과는 의미가 없다
        this.status = AnalysisStatus.AWAITING_USER_CONFIRMATION;
    }

    public void failVision(String message) {
        this.status = AnalysisStatus.VISION_FAILED;
        this.visionProgressJson = null;
        this.failureStage = AnalysisFailureStage.VISION;
        this.failureMessage = message;
    }

    public void startPricing() {
        if (status != AnalysisStatus.AWAITING_USER_CONFIRMATION) {
            throw new InvalidAnalysisStatusException(
                    "가격 계산을 요청할 수 없는 분석 상태입니다. 현재 상태: " + status
            );
        }
        this.status = AnalysisStatus.PRICING_PROCESSING;
    }

    public void recordConfirmedInput(String confirmedInputJson) {
        this.confirmedInputJson = confirmedInputJson;
    }

    public void completePricing(String pricingResultJson) {
        this.pricingResultJson = pricingResultJson;
        this.status = AnalysisStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void failPricing(String message) {
        this.status = AnalysisStatus.PRICING_FAILED;
        this.failureStage = AnalysisFailureStage.PRICING;
        this.failureMessage = message;
    }
}
