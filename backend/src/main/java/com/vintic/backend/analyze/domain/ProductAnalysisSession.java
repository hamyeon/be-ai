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
    @Column(name = "vision_result_json")
    private String visionResultJson;

    // 판매자가 Vision 결과를 확인/수정해 Pricing 요청에 실제로 전달한 최종 입력값
    @Lob
    @Column(name = "confirmed_input_json")
    private String confirmedInputJson;

    @Lob
    @Column(name = "pricing_result_json")
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

    public void startVisionProcessing() {
        this.status = AnalysisStatus.VISION_PROCESSING;
    }

    public void completeVision(String visionResultJson) {
        this.visionResultJson = visionResultJson;
        this.status = AnalysisStatus.AWAITING_USER_CONFIRMATION;
    }

    public void failVision(String message) {
        this.status = AnalysisStatus.VISION_FAILED;
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
