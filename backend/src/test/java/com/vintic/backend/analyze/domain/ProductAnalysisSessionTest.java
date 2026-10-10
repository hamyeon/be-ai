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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CREATED);
        assertThat(session.getStartedAt()).isNotNull();
        assertThat(session.getUserId()).isEqualTo(1L);
    }

    @Test
    void 생성자_본인이면_소유자_검증을_통과한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        assertThat(session.isOwnedBy(1L)).isTrue();
    }

    @Test
    void 다른_사용자면_소유자_검증에_실패한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        assertThat(session.isOwnedBy(2L)).isFalse();
    }

    @Test
    void 이미지_업로드하면_IMAGE_UPLOADED_상태이고_URL이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOADED);
        assertThat(session.getImageUrls()).containsExactly("https://bucket.s3.amazonaws.com/a.jpg");
    }

    @Test
    void 이미지_업로드_실패하면_IMAGE_UPLOAD_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        session.failImageUpload("S3 업로드 실패");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOAD_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.IMAGE_UPLOAD);
        assertThat(session.getFailureMessage()).isEqualTo("S3 업로드 실패");
    }

    @Test
    void 큐에_적재하면_QUEUED_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        session.markQueued();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
    }

    @Test
    void 큐_적재가_실패하면_QUEUE_FAILED_상태이고_실패_단계와_메시지가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        session.failQueueing("Redis 연결 실패");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.QUEUE_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.QUEUE);
        assertThat(session.getFailureMessage()).isEqualTo("Redis 연결 실패");
    }

    @Test
    void Pricing_요청에_전달한_확정_입력값을_기록할_수_있다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        session.recordConfirmedInput("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");

        assertThat(session.getConfirmedInputJson()).isEqualTo("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");
    }

    @Test
    void QUEUED_상태에서_Vision_시작하면_VISION_PROCESSING_상태이고_token이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();

        session.claimVisionProcessing(TOKEN_A);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_A);
    }

    @Test
    void QUEUED가_아닌_상태에서_Vision_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        assertThatThrownBy(() -> session.claimVisionProcessing(TOKEN_A))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void 이미_처리된_세션에서_Vision을_다시_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        // Consumer가 같은 메시지를 중복으로 전달받은 상황을 흉내낸다 - 재실행되면 안 된다.
        assertThatThrownBy(() -> session.claimVisionProcessing(TOKEN_B))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void VISION_PROCESSING_상태에서도_reclaim으로_새_token을_받을_수_있다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();

        session.reclaimVisionProcessing(TOKEN_A);

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_A);
    }

    @Test
    void 이미_종료된_세션은_reclaim할_수_없다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        assertThatThrownBy(() -> session.reclaimVisionProcessing(TOKEN_B))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void Vision_성공하면_AWAITING_USER_CONFIRMATION_상태이고_결과가_저장되고_token이_비워진다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.reclaimVisionProcessing(TOKEN_B);

        assertThatThrownBy(() -> session.failVision(TOKEN_A, "OpenAI 호출 실패"))
                .isInstanceOf(InvalidAnalysisStatusException.class);
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(session.getVisionProcessingToken()).isEqualTo(TOKEN_B);
    }

    @Test
    void 분석_중일_때만_잠정_결과를_받고_끝나면_비운다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        // 분석 시작 전에 온 진행 기록은 버린다
        assertThat(session.recordVisionProgress("{\"completedStages\":1}")).isFalse();
        assertThat(session.getVisionProgressJson()).isNull();

        session.claimVisionProcessing(TOKEN_A);
        assertThat(session.recordVisionProgress("{\"completedStages\":1}")).isTrue();
        assertThat(session.getVisionProgressJson()).isEqualTo("{\"completedStages\":1}");

        session.completeVision(TOKEN_A, "{\"brand\":\"Nike\"}");
        assertThat(session.getVisionProgressJson()).isNull();
        // 끝난 뒤 늦게 도착한 진행 기록도 버린다
        assertThat(session.recordVisionProgress("{\"completedStages\":2}")).isFalse();
        assertThat(session.getVisionProgressJson()).isNull();
    }

    @Test
    void Vision이_실패하면_잠정_결과를_비운다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.recordVisionProgress("{\"completedStages\":1}");

        session.failVision(TOKEN_A, "분석이 제한 시간 안에 끝나지 않았습니다");

        assertThat(session.getVisionProgressJson()).isNull();
    }

    @Test
    void AWAITING_USER_CONFIRMATION_상태에서_Pricing_시작하면_PRICING_PROCESSING_상태이다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");

        session.startPricing();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.PRICING_PROCESSING);
    }

    @Test
    void AWAITING_USER_CONFIRMATION이_아닌_상태에서_Pricing_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);

        assertThatThrownBy(session::startPricing)
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void 이미_완료된_세션에서_Pricing을_다시_시작하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
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
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");
        session.startPricing();

        session.failPricing("시세 데이터 없음");

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.PRICING_FAILED);
        assertThat(session.getFailureStage()).isEqualTo(AnalysisFailureStage.PRICING);
        assertThat(session.getFailureMessage()).isEqualTo("시세 데이터 없음");
    }

    @Test
    void PRICING_PROCESSING이_아닌_상태에서_completePricing을_호출하면_예외가_발생한다() {
        // #127: 취소되어 PRICING_PROCESSING을 벗어난 뒤 늦게 도착한 Pricing 성공 응답을 흉내낸다.
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");
        session.startPricing();
        session.cancel();

        assertThatThrownBy(() -> session.completePricing("{\"recommendedPrice\":300000}"))
                .isInstanceOf(InvalidAnalysisStatusException.class);
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(session.getPricingResultJson()).isNull();
    }

    @Test
    void PRICING_PROCESSING이_아닌_상태에서_failPricing을_호출하면_예외가_발생한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{}");
        session.startPricing();
        session.cancel();

        assertThatThrownBy(() -> session.failPricing("시세 데이터 없음"))
                .isInstanceOf(InvalidAnalysisStatusException.class);
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(session.getFailureStage()).isNull();
    }

    @Test
    void QUEUED_상태의_세션을_취소하면_CANCELLED_상태이고_취소시각이_기록된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();

        session.cancel();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(session.getCancelledAt()).isNotNull();
    }

    @Test
    void VISION_PROCESSING_중에_취소하면_처리_토큰과_진행_결과가_비워진다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.recordVisionProgress("{\"completedStages\":1}");

        session.cancel();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(session.getVisionProcessingToken()).isNull();
        assertThat(session.getVisionProgressJson()).isNull();
    }

    @Test
    void Pricing_완료후에도_등록에_확정_사용되기_전이면_취소할_수_있고_결과가_모두_비워진다() {
        // COMPLETED는 Pricing 완료일 뿐 상품 등록 확정이 아니므로 취소 대상이어야 한다.
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing(TOKEN_A);
        session.completeVision(TOKEN_A, "{\"brand\":\"Nike\"}");
        session.startPricing();
        session.recordConfirmedInput("{\"brand\":\"Nike\"}");
        session.completePricing("{\"recommendedPrice\":300000}");

        session.cancel();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(session.getVisionResultJson()).isNull();
        assertThat(session.getConfirmedInputJson()).isNull();
        assertThat(session.getPricingResultJson()).isNull();
    }

    @Test
    void 반복_취소해도_같은_CANCELLED_상태를_유지한다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.cancel();
        var firstCancelledAt = session.getCancelledAt();

        session.cancel();

        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(session.getCancelledAt()).isEqualTo(firstCancelledAt);
    }

    @Test
    void 이미_상품_등록에_확정_사용된_세션은_취소할_수_없다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.confirmRegistration();

        assertThatThrownBy(session::cancel)
                .isInstanceOf(InvalidAnalysisStatusException.class);
        assertThat(session.getStatus()).isEqualTo(AnalysisStatus.QUEUED);
    }

    @Test
    void 취소된_세션은_등록에_확정_사용할_수_없다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.cancel();

        assertThatThrownBy(session::confirmRegistration)
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }

    @Test
    void 같은_세션으로_두_번_등록을_확정할_수_없다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.confirmRegistration();

        assertThatThrownBy(session::confirmRegistration)
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }
}
