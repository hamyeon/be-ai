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
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markImageUploaded(List.of("https://bucket.s3.amazonaws.com/a.jpg"));

        ProductAnalysisSession saved = sessionRepository.save(session);

        Optional<ProductAnalysisSession> found = sessionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(AnalysisStatus.IMAGE_UPLOADED);
        assertThat(found.get().getImageUrls()).containsExactly("https://bucket.s3.amazonaws.com/a.jpg");
    }

    @Test
    void 완료된_세션의_결과_JSON과_완료시각이_저장된다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.startVisionProcessing();
        session.completeVision("{\"brand\":\"Nike\"}");
        session.startPricing();
        session.completePricing("{\"recommendedPrice\":300000}");

        ProductAnalysisSession saved = sessionRepository.save(session);
        sessionRepository.flush();

        ProductAnalysisSession found = sessionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(found.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
        assertThat(found.getPricingResultJson()).isEqualTo("{\"recommendedPrice\":300000}");
        assertThat(found.getCompletedAt()).isNotNull();
    }
}
