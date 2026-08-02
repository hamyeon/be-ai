package com.vintic.backend.analyze.service;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// @DataJpaTest는 기본적으로 각 테스트를 트랜잭션으로 감싸고 끝나면 롤백한다.
// 이 테스트는 그 반대 상황(REQUIRES_NEW로 기록한 실패 상태가 "상위" 트랜잭션의
// 롤백과 무관하게 실제로 커밋되어 남는지)을 검증해야 하므로, 클래스 레벨에서
// propagation을 NOT_SUPPORTED로 재정의해 기본 롤백 동작을 끈다.
@DataJpaTest
@Import(AnalysisFailureRecorder.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisFailureRecorderTest {

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private AnalysisFailureRecorder failureRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void 상위_트랜잭션이_롤백되어도_REQUIRES_NEW로_기록한_실패_상태는_커밋된_채로_남는다() {
        ProductAnalysisSession session = ProductAnalysisSession.create();
        session.markQueued();
        session.startVisionProcessing();
        ProductAnalysisSession saved = sessionRepository.save(session);
        Long sessionId = saved.getId();

        // 나중에 오케스트레이터 메서드에 @Transactional이 붙는 상황을 흉내낸 "상위 트랜잭션"
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> outerTransaction.executeWithoutResult(status -> {
            failureRecorder.recordVisionFailure(sessionId, "OpenAI 호출 실패");
            throw new RuntimeException("상위 트랜잭션 강제 실패");
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("상위 트랜잭션 강제 실패");

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.VISION_FAILED);
        assertThat(reloaded.getFailureMessage()).isEqualTo("OpenAI 호출 실패");
    }
}
