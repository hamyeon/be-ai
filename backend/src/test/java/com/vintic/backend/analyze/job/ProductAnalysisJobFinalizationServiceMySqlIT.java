package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService.FailOutcome;
import com.vintic.backend.analyze.job.ProductAnalysisJobFinalizationService.FinalizeOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day4 2단계: completeIfOwned(조건부 UPDATE)와 AnalysisResult 저장이 실제로 하나의 외부
// 트랜잭션으로 묶이는지 - 특히 result 저장이 실패했을 때 COMPLETED 전이까지 함께 rollback되는지
// -는 mock 기반 단위 테스트로 증명할 수 없다. 실제 MySQL Testcontainers로 커밋/롤백 경계를
// 직접 관찰한다. 각 테스트 메서드는 Service 호출 자체의 트랜잭션 경계를 그대로 관찰해야 하므로
// 클래스에 @Transactional을 걸지 않는다(걸면 Service의 @Transactional(REQUIRED)이 테스트
// 트랜잭션에 합류해버려 "Service가 스스로 커밋/롤백한다"는 사실을 관찰할 수 없다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductAnalysisJobFinalizationServiceMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ProductAnalysisJobRepository jobRepository;

    @Autowired
    private AnalysisResultRepository resultRepository;

    @Autowired
    private ProductAnalysisJobFinalizationService finalizationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long newProcessingJob(String idempotencyKey, String workerId) {
        ProductAnalysisJob job = jobRepository.saveAndFlush(
                ProductAnalysisJob.create(1L, "uploads/a.jpg", idempotencyKey));
        jdbcTemplate.update(
                "update product_analysis_jobs set status = 'PROCESSING', worker_id = ?, " +
                        "processing_started_at = UTC_TIMESTAMP(6) where id = ?",
                workerId, job.getId());
        return job.getId();
    }

    @Test
    void complete는_owner_일치시_COMPLETED_전이와_result_저장을_한_트랜잭션으로_커밋한다() {
        Long id = newProcessingJob("key-complete-ok", "worker-1");

        FinalizeOutcome outcome = finalizationService.complete(id, "worker-1", "raw-result");

        assertThat(outcome).isEqualTo(FinalizeOutcome.COMPLETED);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from analysis_result where analysis_id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void complete는_owner_불일치시_LEASE_LOST를_반환하고_result를_저장하지_않는다() {
        Long id = newProcessingJob("key-complete-lease-lost", "worker-1");

        FinalizeOutcome outcome = finalizationService.complete(id, "worker-2", "raw-result");

        assertThat(outcome).isEqualTo(FinalizeOutcome.LEASE_LOST);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from analysis_result where analysis_id = ?", Integer.class, id);
        assertThat(count).isEqualTo(0);
    }

    // result INSERT가 UNIQUE 위반으로 실패하면 completeIfOwned가 반영한 COMPLETED 전이까지
    // 함께 rollback돼야 한다 - "Repository 메서드 각각이 독립 트랜잭션을 가진다"는 Day2/3의
    // 전제가 이 Service 메서드 안에서는 적용되지 않고, 전체가 하나의 외부 트랜잭션임을 증명한다.
    @Test
    void result_INSERT가_실패하면_COMPLETED_전이도_함께_rollback된다() {
        Long id = newProcessingJob("key-complete-result-conflict", "worker-1");
        // 이미 같은 analysisId로 결과가 하나 존재하는 상황을 미리 만들어 두 번째 INSERT가
        // UNIQUE 위반으로 실패하게 한다.
        resultRepository.saveAndFlush(AnalysisResult.create(id, "existing-result"));

        assertThatThrownBy(() -> finalizationService.complete(id, "worker-1", "new-result"))
                .isInstanceOf(RuntimeException.class);

        assertThat(jobRepository.findById(id).orElseThrow().getStatus())
                .as("result INSERT 실패 시 completeIfOwned가 반영한 COMPLETED도 롤백돼야 한다")
                .isEqualTo(AnalysisJobStatus.PROCESSING);
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from analysis_result where analysis_id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void fail은_owner_일치시_FAILED를_반환한다() {
        Long id = newProcessingJob("key-fail-ok", "worker-1");

        FailOutcome outcome = finalizationService.fail(id, "worker-1");

        assertThat(outcome).isEqualTo(FailOutcome.FAILED);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.FAILED);
    }

    @Test
    void fail은_owner_불일치시_LEASE_LOST를_반환하고_상태를_바꾸지_않는다() {
        Long id = newProcessingJob("key-fail-lease-lost", "worker-1");

        FailOutcome outcome = finalizationService.fail(id, "worker-2");

        assertThat(outcome).isEqualTo(FailOutcome.LEASE_LOST);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
    }
}
