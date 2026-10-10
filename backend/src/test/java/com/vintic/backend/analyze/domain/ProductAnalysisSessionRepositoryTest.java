package com.vintic.backend.analyze.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductAnalysisSessionRepositoryTest {

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Test
    void 세션을_저장하고_id로_조회할_수_있다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        ProductAnalysisSession saved = sessionRepository.save(session);

        Optional<ProductAnalysisSession> found = sessionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOADED);
        assertThat(found.get().getImageUrls()).containsExactly("https://bucket.s3.amazonaws.com/a.jpg");
    }

    @Test
    void 완료된_세션의_결과_JSON과_완료시각이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing("test-token");
        session.completeVision("test-token", "{\"brand\":\"Nike\"}");
        session.startPricing();
        session.recordConfirmedInput("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");
        session.completePricing("{\"recommendedPrice\":300000}");

        ProductAnalysisSession saved = sessionRepository.save(session);
        sessionRepository.flush();

        ProductAnalysisSession found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(found.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
        assertThat(found.getConfirmedInputJson()).isEqualTo("{\"brand\":\"Nike\",\"conditionGrade\":\"B\"}");
        assertThat(found.getPricingResultJson()).isEqualTo("{\"recommendedPrice\":300000}");
        assertThat(found.getCompletedAt()).isNotNull();
    }

    @Test
    void 이미지_업로드_실패_상태가_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.failImageUpload("S3 업로드 실패");

        ProductAnalysisSession saved = sessionRepository.save(session);
        sessionRepository.flush();

        ProductAnalysisSession found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOAD_FAILED);
        assertThat(found.getFailureStage()).isEqualTo(AnalysisFailureStage.IMAGE_UPLOAD);
        assertThat(found.getFailureMessage()).isEqualTo("S3 업로드 실패");
    }

    @Test
    void 취소된_세션의_CANCELLED_상태와_취소시각이_저장되고_결과_필드는_비워진다() {
        // #127: 신규 상태(CANCELLED)와 신규 컬럼(cancelled_at)이 ddl-auto:update로 정상
        // 매핑되는지, 결과 필드 null 처리가 실제로 저장/재조회에서도 유지되는지 확인한다.
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();
        session.claimVisionProcessing("test-token");
        session.completeVision("test-token", "{\"brand\":\"Nike\"}");

        session.cancel();
        ProductAnalysisSession saved = sessionRepository.save(session);
        sessionRepository.flush();

        ProductAnalysisSession found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(found.getCancelledAt()).isNotNull();
        assertThat(found.getVisionResultJson()).isNull();
    }

    @Test
    void 등록에_확정_사용된_세션의_등록시각이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markQueued();

        session.confirmRegistration();
        ProductAnalysisSession saved = sessionRepository.save(session);
        sessionRepository.flush();

        ProductAnalysisSession found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getRegisteredAt()).isNotNull();
    }
}
