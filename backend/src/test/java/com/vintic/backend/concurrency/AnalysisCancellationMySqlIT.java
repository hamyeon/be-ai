package com.vintic.backend.concurrency;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.domain.VisionAttemptOutcome;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.analyze.service.AnalysisProgressRecorder;
import com.vintic.backend.analyze.service.PricingAttemptCoordinator;
import com.vintic.backend.analyze.service.ProductAnalyzeService;
import com.vintic.backend.analyze.service.VisionAttemptCoordinator;
import com.vintic.backend.analyze.service.VisionFailureStreamRecorder;
import com.vintic.backend.ai.vision.dto.VisionProgress;
import com.vintic.backend.common.exception.AnalysisSessionNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #127: 분석 세션 취소가 비동기 Vision/Pricing 저장 경로와 경쟁할 때 실제 MySQL(행 잠금)에서
// 안전한지 검증한다. 각 코디네이터 메서드 호출이 그 자체로 독립된 트랜잭션이므로,
// VisionAttemptOwnershipMySqlIT와 같은 방식으로 "A가 처리 중에 사용자가 취소한 뒤, A의 뒤늦은
// 응답이 도착"하는 순서를 실제 스레드 경합 없이도 그대로 재현할 수 있다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class AnalysisCancellationMySqlIT {

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
    private VisionAttemptCoordinator coordinator;

    @Autowired
    private AnalysisFailureRecorder failureRecorder;

    @Autowired
    private AnalysisProgressRecorder progressRecorder;

    @Autowired
    private VisionFailureStreamRecorder failureStreamRecorder;

    @Autowired
    private PricingAttemptCoordinator pricingCoordinator;

    private static final Long USER_ID = 1L;

    private Long queuedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create(USER_ID);
        session.markImageUploaded(java.util.List.of("https://example.com/a.jpg"));
        session.markQueued();
        return sessionRepository.save(session).getId();
    }

    @Test
    void 대기중_세션을_취소한_뒤_claim을_시도하면_Vision을_새로_시작하지_않고_ALREADY_FINALIZED로_처리된다() {
        Long sessionId = queuedSessionId();

        productAnalyzeService.cancel(sessionId, USER_ID);

        // AnalysisTaskConsumer.onMessage()가 claim을 먼저 호출하는 것과 동일한 순서 - COMMITTED가
        // 아니면 Vision을 호출하지 않는다.
        VisionAttemptOutcome claimed = coordinator.claim(sessionId, "late-token");

        assertThat(claimed).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
    }

    @Test
    void Vision_처리_중_취소한_뒤_재전달_회수를_시도해도_재분석되지_않고_ALREADY_FINALIZED로_처리된다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");

        productAnalyzeService.cancel(sessionId, USER_ID);

        // AnalysisStreamRecoveryScheduler가 PEL에서 회수해 reclaim을 다시 시도하는 상황을 흉내낸다.
        VisionAttemptOutcome reclaimed = coordinator.reclaim(sessionId, "token-recovery");

        assertThat(reclaimed).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getVisionProcessingToken()).isNull();
    }

    @Test
    void Vision_처리_중_취소한_뒤_뒤늦은_성공_응답이_와도_결과가_저장되지_않는다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");

        productAnalyzeService.cancel(sessionId, USER_ID);

        VisionAttemptOutcome completed = coordinator.complete(sessionId, "token-a", "{\"brand\":\"Nike\"}");

        assertThat(completed).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getVisionResultJson()).isNull();
    }

    @Test
    void Vision_처리_중_취소한_뒤_진행_콜백이_와도_중간_결과가_저장되지_않는다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");

        productAnalyzeService.cancel(sessionId, USER_ID);

        progressRecorder.recordVisionProgress(sessionId, new VisionProgress(1, 3, "Nike", null, null, null));

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getVisionProgressJson()).isNull();
    }

    @Test
    void Vision_처리_중_취소한_뒤_오류_타임아웃_응답이_와도_실패_상태와_실패_횟수가_바뀌지_않고_실패_Stream도_발행되지_않는다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");

        productAnalyzeService.cancel(sessionId, USER_ID);

        // AnalysisTaskConsumer.handleVisionFailure()가 재시도 가치가 있는 오류에서 호출하는 경로.
        var attemptResult = coordinator.incrementFailureAttempt(sessionId, "token-a");
        assertThat(attemptResult.outcome()).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        assertThat(attemptResult.attemptCount()).isZero();

        // 재시도 가치가 없는 오류이거나 상한을 넘겼을 때 최종 실패를 기록하는 경로.
        VisionAttemptOutcome failed = failureRecorder.recordVisionFailure(sessionId, "token-a", "타임아웃");
        assertThat(failed).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getFailureStage()).isNull();
        assertThat(reloaded.getFailureMessage()).isNull();
        assertThat(reloaded.getVisionFailureAttemptCount()).isZero();
        // VISION_FAILED가 아니므로 발행 대상 이벤트 자체가 없다 - 실패 Stream에 발행되지 않는다.
        assertThat(failureStreamRecorder.pendingFailureEvent(sessionId)).isEmpty();
    }

    @Test
    void Vision_완료가_먼저_커밋되면_그_결과를_취소가_비우고_상태가_되돌아가지_않는다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");
        VisionAttemptOutcome completed = coordinator.complete(sessionId, "token-a", "{\"brand\":\"Nike\"}");
        assertThat(completed).isEqualTo(VisionAttemptOutcome.COMMITTED);

        productAnalyzeService.cancel(sessionId, USER_ID);

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getVisionResultJson()).isNull();

        // 취소가 이미 확정된 뒤 뒤늦게 다시 완료를 시도해도(ACK 재시도 등) 되돌리지 못한다.
        VisionAttemptOutcome lateRetry = coordinator.complete(sessionId, "token-a", "{\"brand\":\"다른 값\"}");
        assertThat(lateRetry).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getVisionResultJson()).isNull();
    }

    @Test
    void Pricing_처리_중_취소하면_뒤늦은_Pricing_성공_응답이_결과를_저장하지_못한다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");
        coordinator.complete(sessionId, "token-a", "{}");
        pricingCoordinator.startOwned(sessionId, USER_ID, "{\"brand\":\"Nike\"}");

        productAnalyzeService.cancel(sessionId, USER_ID);

        boolean saved = pricingCoordinator.tryCompletePricing(sessionId, "{\"recommendedPrice\":300000}");

        assertThat(saved).isFalse();
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getPricingResultJson()).isNull();
    }

    @Test
    void Pricing_처리_중_취소하면_뒤늦은_Pricing_실패_응답도_실패_상태로_되돌리지_못한다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");
        coordinator.complete(sessionId, "token-a", "{}");
        pricingCoordinator.startOwned(sessionId, USER_ID, "{\"brand\":\"Nike\"}");

        productAnalyzeService.cancel(sessionId, USER_ID);

        boolean failRecorded = pricingCoordinator.tryFailPricing(sessionId, "시세 데이터 없음");

        assertThat(failRecorded).isFalse();
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(reloaded.getFailureMessage()).isNull();
    }

    @Test
    void 취소된_세션은_취소를_반복해도_같은_상태를_유지하고_다른_사용자는_취소할_수_없다() {
        Long sessionId = queuedSessionId();

        productAnalyzeService.cancel(sessionId, USER_ID);
        var repeated = productAnalyzeService.cancel(sessionId, USER_ID); // 멱등 - 같은 성공 상태

        assertThat(repeated.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> productAnalyzeService.cancel(sessionId, 999L))
                .isInstanceOf(AnalysisSessionNotFoundException.class);
    }

    @Test
    void 한_세션의_취소는_다른_진행중인_세션에_영향을_주지_않는다() {
        Long cancelledSessionId = queuedSessionId();
        Long otherSessionId = queuedSessionId();
        coordinator.claim(otherSessionId, "token-other");

        productAnalyzeService.cancel(cancelledSessionId, USER_ID);

        // 취소되지 않은 다른 세션은 정상적으로 완료될 수 있다.
        VisionAttemptOutcome completed = coordinator.complete(otherSessionId, "token-other", "{\"brand\":\"Nike\"}");

        assertThat(completed).isEqualTo(VisionAttemptOutcome.COMMITTED);
        ProductAnalysisSession other = sessionRepository.findById(otherSessionId).orElseThrow();
        assertThat(other.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(other.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
    }
}
