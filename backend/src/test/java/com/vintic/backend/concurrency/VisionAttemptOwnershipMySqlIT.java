package com.vintic.backend.concurrency;

import com.vintic.backend.analyze.domain.AnalysisStatus;
import com.vintic.backend.analyze.domain.ProductAnalysisSession;
import com.vintic.backend.analyze.domain.ProductAnalysisSessionRepository;
import com.vintic.backend.analyze.domain.VisionAttemptOutcome;
import com.vintic.backend.analyze.service.AnalysisFailureRecorder;
import com.vintic.backend.analyze.service.VisionAttemptCoordinator;
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

// AI 분석 Worker의 PEL(pending entries list) 회수와 소유권(fencing token) 검사를 실제 MySQL로
// 검증한다. VisionAttemptCoordinator/AnalysisFailureRecorder는 각 호출마다 findByIdForUpdate +
// 짧은 트랜잭션으로 원자적 갱신을 하므로, 여기서는 실제 스레드 경합을 만들지 않고도(claim ->
// reclaim -> complete를 순서대로 호출) "A가 Vision 호출 중에 B가 재선점한 뒤, A가 새
// 트랜잭션에서 재조회해 완료를 시도"하는 상황을 그대로 재현할 수 있다 - 각 코디네이터 메서드
// 호출이 그 자체로 독립된 트랜잭션이기 때문이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class VisionAttemptOwnershipMySqlIT {

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
    private VisionAttemptCoordinator coordinator;

    @Autowired
    private AnalysisFailureRecorder failureRecorder;

    private Long queuedSessionId() {
        ProductAnalysisSession session = ProductAnalysisSession.create(1L);
        session.markImageUploaded(java.util.List.of("https://example.com/a.jpg"));
        session.markQueued();
        return sessionRepository.save(session).getId();
    }

    @Test
    void A가_Vision_호출_중에_B가_재선점하면_A의_뒤늦은_완료는_반영되지_않고_B의_재선점_결과가_유지된다() {
        Long sessionId = queuedSessionId();

        // A: 정상 배달로 claim (Vision 호출을 시작하려는 시점 - 아직 완료 전)
        VisionAttemptOutcome claimedByA = coordinator.claim(sessionId, "token-a");
        assertThat(claimedByA).isEqualTo(VisionAttemptOutcome.COMMITTED);

        // A가 Vision 응답을 기다리는 동안, PEL 회수 스케줄러가 B로 재선점한다.
        VisionAttemptOutcome reclaimedByB = coordinator.reclaim(sessionId, "token-b");
        assertThat(reclaimedByB).isEqualTo(VisionAttemptOutcome.COMMITTED);

        // A의 Vision 호출이 뒤늦게 성공해, A가 새 트랜잭션에서 재조회해 완료를 시도한다.
        VisionAttemptOutcome lateCompleteByA = coordinator.complete(sessionId, "token-a", "{\"brand\":\"A의 결과\"}");
        assertThat(lateCompleteByA).isEqualTo(VisionAttemptOutcome.OWNERSHIP_LOST);

        // DB에는 B의 재선점 결과만 남아야 한다 - A의 결과로 덮어써지지 않는다.
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(reloaded.getVisionProcessingToken()).isEqualTo("token-b");
        assertThat(reloaded.getVisionResultJson()).isNull();
    }

    @Test
    void B가_재선점해_완료하면_정상적으로_반영된다() {
        Long sessionId = queuedSessionId();

        coordinator.claim(sessionId, "token-a");
        coordinator.reclaim(sessionId, "token-b");

        VisionAttemptOutcome completedByB = coordinator.complete(sessionId, "token-b", "{\"brand\":\"Nike\"}");
        assertThat(completedByB).isEqualTo(VisionAttemptOutcome.COMMITTED);

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
        assertThat(reloaded.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
        assertThat(reloaded.getVisionProcessingToken()).isNull();
    }

    @Test
    void Worker가_죽어_QUEUED_상태로_PEL에만_남은_세션도_다른_Worker가_회수해_완료할_수_있다() {
        // Worker가 claim을 DB에 저장하기도 전에 죽어, DB는 QUEUED에 머물지만 Redis PEL에는
        // 이미 배달된 메시지로 남아있는 상황(startVisionProcessing 저장 실패와 동일한 틈).
        Long sessionId = queuedSessionId();

        VisionAttemptOutcome reclaimed = coordinator.reclaim(sessionId, "token-recovery");
        assertThat(reclaimed).isEqualTo(VisionAttemptOutcome.COMMITTED);

        VisionAttemptOutcome completed = coordinator.complete(sessionId, "token-recovery", "{}");
        assertThat(completed).isEqualTo(VisionAttemptOutcome.COMMITTED);

        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.AWAITING_USER_CONFIRMATION);
    }

    @Test
    void DB_완료_후_ACK만_실패해_재전달된_메시지의_뒤늦은_완료_시도는_ALREADY_FINALIZED로_안전하게_처리된다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");
        coordinator.complete(sessionId, "token-a", "{\"brand\":\"Nike\"}");

        // XACK 자체가 실패해 같은 메시지(같은 token)가 재전달됐다고 가정 - 다시 완료를 시도한다.
        VisionAttemptOutcome redeliveredOutcome = coordinator.complete(sessionId, "token-a", "{\"brand\":\"다른 값\"}");

        assertThat(redeliveredOutcome).isEqualTo(VisionAttemptOutcome.ALREADY_FINALIZED);
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        // 재전달된 시도가 결과를 덮어쓰지 않았어야 한다.
        assertThat(reloaded.getVisionResultJson()).isEqualTo("{\"brand\":\"Nike\"}");
    }

    @Test
    void AnalysisFailureRecorder도_소유권을_상실하면_실패_기록을_반영하지_않는다() {
        Long sessionId = queuedSessionId();
        coordinator.claim(sessionId, "token-a");
        coordinator.reclaim(sessionId, "token-b");

        VisionAttemptOutcome outcome = failureRecorder.recordVisionFailure(sessionId, "token-a", "OpenAI 오류");

        assertThat(outcome).isEqualTo(VisionAttemptOutcome.OWNERSHIP_LOST);
        ProductAnalysisSession reloaded = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisStatus.VISION_PROCESSING);
        assertThat(reloaded.getVisionProcessingToken()).isEqualTo("token-b");
        assertThat(reloaded.getFailureMessage()).isNull();
    }
}
