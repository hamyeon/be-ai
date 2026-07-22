package com.vintic.backend.analyze.domain;

import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAnalysisSessionTest {

    @Test
    void 세션을_생성하면_CREATED_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CREATED);
        assertThat(session.getStartedAt()).isNotNull();
    }

    @Test
    void 이미지_업로드하면_IMAGE_UPLOADED_상태이고_URL이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();

        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOADED);
        assertThat(session.getImageUrls()).containsExactly("https://bucket.s3.amazonaws.com/a.jpg");
    }

    @Test
    void 이미지_업로드_실패하면_IMAGE_UPLOAD_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();

        session.failImageUpload("S3 업로드 실패");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOAD_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.IMAGE_UPLOAD);
        assertThat(session.getFailureMessage()).isEqualTo("S3 업로드 실패");
    }

    @Test
    void Pricing_요청에_전달한_확정_입력값을_기록할_수_있다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");

        session.recordConfirmedInput("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");

        assertThat(session.getConfirmedInputJson()).isEqualTo("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");
    }

    @Test
    void Vision_시작하면_VISION_PROCESSING_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();

        session.startVisionProcessing();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
    }

    @Test
    void Vision_성공하면_AWAITING_USER_CONFIRMATION_상태이고_결과가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();

        session.completeVision("{\"brand\":\"Nike\"}");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(session.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
    }

    @Test
    void Vision_실패하면_VISION_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();

        session.failVision("OpenAI 호출 실패");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.VISION);
        assertThat(session.getFailureMessage()).isEqualTo("OpenAI 호출 실패");
    }

    @Test
    void AWAITING_USER_CONFIRMATION_상태에서_Pricing_시작하면_PRICING_PROCESSING_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");

        session.startPricing();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.PRICING_PROCESSING);
    }

    @Test
    void AWAITING_USER_CONFIRMATION이_아닌_상태에서_Pricing_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();

        assertThatThrownBy(session::startPricing)
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void 이미_완료된_세션에서_Pricing을_다시_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");
        session.startPricing();
        session.completePricing("{}");

        assertThatThrownBy(session::startPricing)
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void Pricing_성공하면_COMPLETED_상태이고_결과와_완료시각이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");
        session.startPricing();

        session.completePricing("{\"recommendedPrice\":300000}");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(session.getPricingResultJson()).isEqualTo("{\"recommendedPrice\":300000}");
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void Pricing_실패하면_PRICING_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{}");
        session.startPricing();

        session.failPricing("시세 데이터 없음");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.PRICING_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.PRICING);
        assertThat(session.getFailureMessage()).isEqualTo("시세 데이터 없음");
    }
}
