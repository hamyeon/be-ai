package com.vintic.backend.analyze.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day2 1단계 검증: ProductAnalysisJob의 조건부 UPDATE 전이가 허용된 상태에서만 1건 반영되고
// 금지된 상태(PROCESSING/COMPLETED)에서는 0건을 반환하는지, UNIQUE(user_id, idempotency_key)가
// 실제 MySQL(InnoDB)에서 걸리는지 확인한다. H2가 아니라 Testcontainers MySQL을 쓴다.
// @Modifying 쿼리는 트랜잭션이 있어야 실행되므로 테스트 메서드를 트랜잭션으로 감싼다
// (각 테스트 종료 후 롤백되어 테스트 간 데이터도 자동으로 격리된다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
@Transactional
class ProductAnalysisJobRepositoryMySqlIT {

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
    private JdbcTemplate jdbcTemplate;

    private ProductAnalysisJob newJob(String idempotencyKey) {
        return jobRepository.save(ProductAnalysisJob.create(1L, "uploads/a.jpg", idempotencyKey));
    }

    // 이번 단계 스코프에 markCompleted/markFailed가 없어 COMPLETED로 만들 정상 경로가 없으므로,
    // 차단 테스트의 시작 상태만 raw SQL로 강제한다. 프로덕션 코드는 이 방식으로 상태를 바꾸지 않는다.
    private void forceStatus(Long id, AnalysisJobStatus status) {
        jdbcTemplate.update("update product_analysis_jobs set status = ? where id = ?", status.name(), id);
    }

    @Test
    void PENDING에서_markQueued하면_1건_반영되고_QUEUED가_된다() {
        ProductAnalysisJob job = newJob("key-pending-queued");

        int updated = jobRepository.markQueued(job.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.QUEUED);
    }

    @Test
    void PUBLISH_FAILED에서_markQueued하면_1건_반영된다() {
        ProductAnalysisJob job = newJob("key-publishfailed-queued");
        forceStatus(job.getId(), AnalysisJobStatus.PUBLISH_FAILED);

        int updated = jobRepository.markQueued(job.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.QUEUED);
    }

    @Test
    void PENDING에서_markPublishFailed하면_1건_반영된다() {
        ProductAnalysisJob job = newJob("key-pending-publishfailed");

        int updated = jobRepository.markPublishFailed(job.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PUBLISH_FAILED);
    }

    @Test
    void PENDING에서_markProcessing하면_1건_반영되고_PROCESSING이_된다() {
        ProductAnalysisJob job = newJob("key-pending-processing");

        int updated = jobRepository.markProcessing(job.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void QUEUED에서_markProcessing하면_1건_반영된다() {
        ProductAnalysisJob job = newJob("key-queued-processing");
        forceStatus(job.getId(), AnalysisJobStatus.QUEUED);

        int updated = jobRepository.markProcessing(job.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    // 발행 응답 유실(PUBLISH_FAILED로 도장 찍힘) 이후에도 Worker가 실제로는 메시지를 받아
    // PROCESSING으로 넘어갈 수 있어야 하는 응답유실 race 경로
    @Test
    void PUBLISH_FAILED에서_markProcessing하면_1건_반영된다() {
        ProductAnalysisJob job = newJob("key-publishfailed-processing");
        forceStatus(job.getId(), AnalysisJobStatus.PUBLISH_FAILED);

        int updated = jobRepository.markProcessing(job.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    // Worker가 먼저 PROCESSING으로 선점한 뒤 Producer가 뒤늦게 QUEUED로 덮어쓰지 못하는지 검증
    @Test
    void PROCESSING에서_markQueued하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-processing-queued-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.PROCESSING);

        int updated = jobRepository.markQueued(job.getId());

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void COMPLETED에서_markQueued하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-completed-queued-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.COMPLETED);

        int updated = jobRepository.markQueued(job.getId());

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.COMPLETED);
    }

    @Test
    void PROCESSING에서_markPublishFailed하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-processing-publishfailed-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.PROCESSING);

        int updated = jobRepository.markPublishFailed(job.getId());

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void COMPLETED에서_markPublishFailed하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-completed-publishfailed-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.COMPLETED);

        int updated = jobRepository.markPublishFailed(job.getId());

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.COMPLETED);
    }

    @Test
    void 같은_userId와_idempotencyKey로_두번_저장하면_UNIQUE_위반으로_실패한다() {
        jobRepository.saveAndFlush(ProductAnalysisJob.create(1L, "uploads/a.jpg", "dup-key"));

        assertThatThrownBy(() ->
                jobRepository.saveAndFlush(ProductAnalysisJob.create(1L, "uploads/b.jpg", "dup-key"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // Day 4(Lease/fencing) 필드(session_id/worker_id/processing_started_at)가 ddl-auto:update로
    // 새로 생성된 테이블에 잘못 남아있지 않은지 확인한다.
    @Test
    void Day2_범위가_아닌_컬럼은_생성되지_않는다() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns " +
                        "where table_schema = database() and table_name = 'product_analysis_jobs' " +
                        "and column_name in ('session_id', 'worker_id', 'processing_started_at')",
                Integer.class
        );

        assertThat(count).isEqualTo(0);
    }
}
