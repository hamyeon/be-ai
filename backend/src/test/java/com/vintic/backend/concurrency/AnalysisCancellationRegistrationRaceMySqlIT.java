package com.vintic.backend.concurrency;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import com.vintic.backend.common.exception.InvalidAnalysisStatusException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #127: 분석 세션 취소와 상품 등록 확정(ProductRegistrationService.createProduct()이 호출하는
// ProductAnalysisSession.confirmRegistration())의 경쟁을 실제 MySQL 행 잠금으로 검증한다.
//
// confirmSessionForRegistration()은 ProductRegistrationService.createProduct()가 실제로 하는
// 일(findByIdForUpdate로 잠그고 소유권 확인 후 confirmRegistration() 호출)을 그대로 따라한다 -
// User/Product/Auction까지 모두 실제로 저장해야 하는 전체 등록 흐름은 ProductRegistrationServiceTest가
// Mockito로 이미 검증하므로, 여기서는 두 서비스가 공유하는 "세션 행 잠금 + 상태 가드" 원자성만
// 실제 MySQL로 집중 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AnalysisCancellationRegistrationRaceMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ProductAnalysisSessionRepository sessionRepository;

    @Autowired
    private ProductAnalyzeService productAnalyzeService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final Long USER_ID = 1L;

    private Long completedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create(USER_ID);
        session.markQueued();
        session.claimVisionProcessing("token");
        session.completeVision("token", "{\"brand\":\"Nike\"}");
        session.startPricing();
        session.completePricing("{\"recommendedPrice\":300000}");
        return sessionRepository.save(session).getId();
    }

    // ProductRegistrationService.createProduct()의 세션 확정 단계와 동일한 잠금/가드/저장 순서.
    // findByIdForUpdate는 비관적 락 쿼리라 활성 트랜잭션이 필요하다 - createProduct()는
    // @Transactional 메서드라 자동으로 보장되지만, 이 테스트 헬퍼는 평범한 메서드이므로
    // TransactionTemplate으로 직접 트랜잭션 경계를 만들어 같은 조건을 재현한다.
    private void confirmSessionForRegistration(Long sessionId, Long userId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ProductAnalysisSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new AnalysisSessionNotFoundException("분석 세션을 찾을 수 없습니다. analysisId: " + sessionId));
            if (!session.isOwnedBy(userId)) {
                throw new AnalysisSessionNotFoundException("분석 세션을 찾을 수 없습니다. analysisId: " + sessionId);
            }
            session.confirmRegistration();
            sessionRepository.save(session);
        });
    }

    @Test
    void 등록_확정이_먼저_커밋되면_이후_취소_시도는_거절되고_등록된_상태가_유지된다() {
        Long sessionId = completedSessionId();

        confirmSessionForRegistration(sessionId, USER_ID);

        assertThatThrownBy(() -> productAnalyzeService.cancel(sessionId, USER_ID))
                .isInstanceOf(InvalidAnalysisStatusException.class);

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(reloaded.getRegisteredAt()).isNotNull();
        // 등록 확정된 세션의 결과는 취소 거절 시도로도 지워지지 않는다.
        assertThat(reloaded.getPricingResultJson()).isEqualTo("{\"recommendedPrice\":300000}");
    }

    @Test
    void 취소가_먼저_커밋되면_이후_등록_확정_시도는_거절되고_상품_등록에_쓰이지_못한다() {
        Long sessionId = completedSessionId();

        productAnalyzeService.cancel(sessionId, USER_ID);

        assertThatThrownBy(() -> confirmSessionForRegistration(sessionId, USER_ID))
                .isInstanceOf(InvalidAnalysisStatusException.class);

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getRegisteredAt()).isNull();
    }

    @Test
    void 같은_세션으로_두_번_등록을_확정할_수_없다() {
        Long sessionId = completedSessionId();

        confirmSessionForRegistration(sessionId, USER_ID);

        assertThatThrownBy(() -> confirmSessionForRegistration(sessionId, USER_ID))
                .isInstanceOf(InvalidAnalysisStatusException.class);
    }
}
