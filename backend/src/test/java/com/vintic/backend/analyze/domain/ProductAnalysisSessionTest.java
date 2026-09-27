package com.vintic.backend.analyze.domain;

import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductAnalysisSessionTest {

    private static final String TOKEN_A = "token-a";
    private static final String TOKEN_B = "token-b";

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
    void 큐에_적재하면_QUEUED_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        session.markQueued();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
    }

    @Test
    void 큐_적재가_실패하면_QUEUE_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        session.failQueueing("Redis 연결 실패");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.QUEUE_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.QUEUE);
        assertThat(session.getFailureMessage()).isEqualTo("Redis 연결 실패");
    }

    @Test
    void Pricing_요청에_전달한_확정_입력값을_기록할_수_있다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        session.recordConfirmedInput("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");

        assertThat(session.getConfirmedInputJson()).isEqualTo("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");
    }

    @Test
    void QUEUED_상태에서_Vision_시작하면_VISION_PROCESSING_상태이고_token이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();

        session.claimVisionProcessing(TOKEN_A);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_A);
    }

    @Test
    void QUEUED가_아닌_상태에서_Vision_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();

        assertThatThrownBy(() -> session.claimVisionProcessing(TOKEN_A))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void 이미_처리된_세션에서_Vision을_다시_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        // Consumer가 같은 메시지를 중복으로 전달받은 상황을 흉내낸다 - 재실행되면 안 된다.
        assertThatThrownBy(() -> session.claimVisionProcessing(TOKEN_B))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void VISION_PROCESSING_상태에서도_reclaim으로_새_token을_받을_수_있다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);

        // PEL 회수: 이전 Worker가 죽었을 수 있다고 보고 새 Worker가 재선점한다.
        session.reclaimVisionProcessing(TOKEN_B);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_B);
    }

    @Test
    void QUEUED_상태에서도_reclaim으로_시작할_수_있다() {
        // Worker가 claim 저장 직전에 죽어 DB가 QUEUED에 머문 채 PEL에만 남은 상황을 흉내낸다.
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();

        session.reclaimVisionProcessing(TOKEN_A);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_A);
    }

    @Test
    void 이미_종료된_세션은_reclaim할_수_없다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        assertThatThrownBy(() -> session.reclaimVisionProcessing(TOKEN_B))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void Vision_성공하면_AWAITING_USER_CONFIRMATION_상태이고_결과가_저장되고_token이_비워진다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);

        session.completeVision(TOKEN_A, "{\"brand\":\"Nike\"}");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(session.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
        assertThat(session.getVisionProcessingToken()).isNull();
    }

    @Test
    void 다른_token으로_Vision_완료를_시도하면_예외가_발생한다() {
        // A가 claim한 뒤 B가 reclaim으로 재선점한 상황에서, A가 뒤늦게 완료를 시도하는 경우.
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.reclaimVisionProcessing(TOKEN_B);

        assertThatThrownBy(() -> session.completeVision(TOKEN_A, "{\"brand\":\"Nike\"}"))
                .isInstanceOf(InvalidAnalysisStatusException.class);
        // B의 재선점 결과가 그대로 유지되어야 한다 - A의 뒤늦은 시도가 덮어쓰지 않는다.
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_B);
    }

    @Test
    void Vision_실패하면_VISION_FAILED_상태이고_실패_단계와_메시지가_저장되고_token이_비워진다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);

        session.failVision(TOKEN_A, "OpenAI 호출 실패");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.VISION);
        assertThat(session.getFailureMessage()).isEqualTo("OpenAI 호출 실패");
        assertThat(session.getVisionProcessingToken()).isNull();
    }

    @Test
    void 다른_token으로_Vision_실패를_기록하려_하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.reclaimVisionProcessing(TOKEN_B);

        assertThatThrownBy(() -> session.failVision(TOKEN_A, "OpenAI 호출 실패"))
                .isInstanceOf(InvalidAnalysisStatusException.class);
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_B);
    }

    @Test
    void AWAITING_USER_CONFIRMATION_상태에서_Pricing_시작하면_PRICING_PROCESSING_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

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
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");
        session.startPricing();
        session.completePricing("{}");

        assertThatThrownBy(session::startPricing)
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void Pricing_성공하면_COMPLETED_상태이고_결과와_완료시각이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");
        session.startPricing();

        session.completePricing("{\"recommendedPrice\":300000}");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(session.getPricingResultJson()).isEqualTo("{\"recommendedPrice\":300000}");
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void Pricing_실패하면_PRICING_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");
        session.startPricing();

        session.failPricing("시세 데이터 없음");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.PRICING_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.PRICING);
        assertThat(session.getFailureMessage()).isEqualTo("시세 데이터 없음");
    }
}
