package com.vintic.backend.analyze.job;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private AnalysisResultRepository analysisResultRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ProductAnalysisJob newJob(String idempotencyKey) {
        return jobRepository.save(ProductAnalysisJob.create(1L, "uploads/a.jpg", idempotencyKey));
    }

    // 조건부 UPDATE를 각각 독립적으로 검증하기 위해 시작 상태를 raw SQL로 강제한다 -
    // 예를 들어 completeIfOwned를 claimForProcessing 경유 없이 PROCESSING부터 바로 검증한다.
    // 프로덕션 코드는 이 방식으로 상태를 바꾸지 않는다.
    private void forceStatus(Long id, AnalysisJobStatus status) {
        jdbcTemplate.update("update product_analysis_jobs set status = ? where id = ?", status.name(), id);
    }

    // claim/fencing 테스트용 PROCESSING 상태 강제 설정. processingStartedAt에 null을 넘기면
    // Day4 이전(컬럼 도입 전)에 생성됐을 법한 legacy PROCESSING row를 재현한다.
    private void forceProcessing(Long id, String workerId, LocalDateTime processingStartedAt) {
        jdbcTemplate.update(
                "update product_analysis_jobs set status = ?, worker_id = ?, processing_started_at = ? where id = ?",
                AnalysisJobStatus.PROCESSING.name(), workerId, processingStartedAt, id);
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

    // PENDING/QUEUED/PUBLISH_FAILED에서 PROCESSING으로의 최초 선점은 Day4부터 unfenced
    // markProcessing이 아니라 claimForProcessing만 쓴다 - 동일 시나리오는 아래
    // "PENDING/QUEUED/PUBLISH_FAILED에서_claimForProcessing하면..." 테스트가 검증한다.

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

    // PROCESSING + owner 일치에서의 completeIfOwned/failIfOwned 성공은 아래
    // "owner_일치시_completeIfOwned/failIfOwned하면..." 테스트가 검증한다(Day4 fencing 포함).

    // Worker가 PROCESSING을 선점하기도 전에(중복 배달 등으로) completeIfOwned/failIfOwned가
    // 먼저 오는 경우를 차단한다 - Handler는 이 0건을 보고 메시지를 지우지 않아야 한다(RETAIN).
    // worker_id가 아직 없는(claim 전) row이므로 owner 일치 여부와 무관하게 status 조건만으로도
    // 0건이어야 한다.
    @Test
    void QUEUED에서_completeIfOwned하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-queued-completeIfOwned-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.QUEUED);

        int updated = jobRepository.completeIfOwned(job.getId(), "worker-1");

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.QUEUED);
    }

    @Test
    void QUEUED에서_failIfOwned하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-queued-failIfOwned-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.QUEUED);

        int updated = jobRepository.failIfOwned(job.getId(), "worker-1");

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.QUEUED);
    }

    // 이미 종료 상태(COMPLETED/FAILED)에 도달한 뒤 중복 배달된 메시지가 다시 같은 조건부
    // UPDATE를 시도해도 0건이어야 하고, 이미 확정된 최종 상태가 덮어써지지 않아야 한다.
    @Test
    void COMPLETED에서_failIfOwned하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-completed-failIfOwned-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.COMPLETED);

        int updated = jobRepository.failIfOwned(job.getId(), "worker-1");

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.COMPLETED);
    }

    @Test
    void FAILED에서_completeIfOwned하면_0건_반영되고_상태가_유지된다() {
        ProductAnalysisJob job = newJob("key-failed-completeIfOwned-blocked");
        forceStatus(job.getId(), AnalysisJobStatus.FAILED);

        int updated = jobRepository.completeIfOwned(job.getId(), "worker-1");

        assertThat(updated).isEqualTo(0);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.FAILED);
    }

    @Test
    void 같은_userId와_idempotencyKey로_두번_저장하면_UNIQUE_위반으로_실패한다() {
        jobRepository.saveAndFlush(ProductAnalysisJob.create(1L, "uploads/a.jpg", "dup-key"));

        assertThatThrownBy(() ->
                jobRepository.saveAndFlush(ProductAnalysisJob.create(1L, "uploads/b.jpg", "dup-key"))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    // Day4 lease/fencing 컬럼(worker_id/processing_started_at)은 ddl-auto:update로 실제 생성돼야
    // 하고, 여전히 범위 밖인 session_id(ProductAnalysisSession 개념) 같은 컬럼은 이 테이블에
    // 섞여 들어오면 안 된다.
    @Test
    void Day4_lease_컬럼은_생성되고_범위_밖_컬럼은_생성되지_않는다() {
        List<String> day4Columns = jdbcTemplate.queryForList(
                "select column_name from information_schema.columns " +
                        "where table_schema = database() and table_name = 'product_analysis_jobs' " +
                        "and column_name in ('worker_id', 'processing_started_at')",
                String.class
        );
        assertThat(day4Columns).containsExactlyInAnyOrder("worker_id", "processing_started_at");

        Integer outOfScopeCount = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns " +
                        "where table_schema = database() and table_name = 'product_analysis_jobs' " +
                        "and column_name in ('session_id')",
                Integer.class
        );
        assertThat(outOfScopeCount).isEqualTo(0);
    }

    // ---- Day4 claimForProcessing: 신규/stale 원자적 claim (DB UTC 시계 기준) ----

    // stale 여부 판정 기준 기간. claimForProcessing은 절대시각이 아니라 이 상대 기간만 받는다 -
    // processing_started_at 기록/판정을 모두 MySQL 서버의 UTC_TIMESTAMP(6)로 통일해 호출자 JVM의
    // 시계·timezone 설정과 무관하게 정합성을 보장하기 위함이다.
    private static final long FIVE_MINUTES_MICROS = TimeUnit.MINUTES.toMicros(5);

    // claim/fencing 테스트에서 "N초 전에 선점됨"을 DB의 UTC 시계 기준으로 직접 재현한다. JVM에서
    // 계산한 LocalDateTime을 절대시각으로 써넣지 않고 MySQL의 UTC_TIMESTAMP(6) 표현식으로 상대
    // 계산해, 이 테스트 자체가 JVM timezone에 좌우되지 않도록 한다.
    private void forceProcessingStaleBy(Long id, String workerId, long secondsAgo) {
        jdbcTemplate.update(
                "update product_analysis_jobs set status = ?, worker_id = ?, " +
                        "processing_started_at = UTC_TIMESTAMP(6) - INTERVAL ? SECOND where id = ?",
                AnalysisJobStatus.PROCESSING.name(), workerId, secondsAgo, id);
    }

    // processing_started_at과 현재 DB UTC 시계 사이의 microsecond 차이를 DB 쪽에서 직접 계산해
    // 돌려준다. 방금 claim이 실제로 UTC_TIMESTAMP(6)를 썼는지 확인할 때, JVM이 읽어들인
    // LocalDateTime을 JVM 시계와 비교하는 방식(zone 불일치로 오판 가능)을 피하기 위함이다.
    private long microsSinceProcessingStartedAtUtc(Long id) {
        return jdbcTemplate.queryForObject(
                "select TIMESTAMPDIFF(MICROSECOND, processing_started_at, UTC_TIMESTAMP(6)) " +
                        "from product_analysis_jobs where id = ?",
                Long.class, id);
    }

    @Test
    void PENDING에서_claimForProcessing하면_1건_반영되고_workerId와_processingStartedAt이_채워진다() {
        ProductAnalysisJob job = newJob("key-claim-pending");

        int updated = jobRepository.claimForProcessing(job.getId(), "worker-1", FIVE_MINUTES_MICROS);

        assertThat(updated).isEqualTo(1);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-1");
        assertThat(reloaded.getProcessingStartedAt()).isNotNull();
    }

    @Test
    void QUEUED에서_claimForProcessing하면_1건_반영된다() {
        ProductAnalysisJob job = newJob("key-claim-queued");
        forceStatus(job.getId(), AnalysisJobStatus.QUEUED);

        int updated = jobRepository.claimForProcessing(job.getId(), "worker-1", FIVE_MINUTES_MICROS);

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void PUBLISH_FAILED에서_claimForProcessing하면_1건_반영된다() {
        ProductAnalysisJob job = newJob("key-claim-publishfailed");
        forceStatus(job.getId(), AnalysisJobStatus.PUBLISH_FAILED);

        int updated = jobRepository.claimForProcessing(job.getId(), "worker-1", FIVE_MINUTES_MICROS);

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.PROCESSING);
    }

    @Test
    void fresh_PROCESSING은_claimForProcessing으로_재선점되지_않는다() {
        ProductAnalysisJob job = newJob("key-claim-fresh-processing");
        forceProcessingStaleBy(job.getId(), "worker-old", 0);

        int updated = jobRepository.claimForProcessing(job.getId(), "worker-new", FIVE_MINUTES_MICROS);

        assertThat(updated).isEqualTo(0);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-old");
    }

    // 실제로 재현됐던 결함에 대한 회귀 테스트: processing_started_at을 기록하는 claim(Worker A)과
    // stale 여부를 판정하는 claim(Worker B)을 절대시각 없이 연속으로 실행한다. 이 실행 환경은
    // Testcontainers MySQL(UTC 세션) vs JVM 기본 timezone(Asia/Seoul)이 달라, 이전에 절대시각
    // staleBefore를 JVM에서 계산해 넘기던 구현에서는 방금 선점된 row가 9시간 "오래된" stale로
    // 잘못 판정되어 두 번째 claim도 성공(0건이어야 할 것이 1건)해버렸다.
    @Test
    void 방금_claim된_PROCESSING은_서로_다른_시점의_claim_시도에서도_재선점되지_않는다() {
        ProductAnalysisJob job = newJob("key-claim-cross-jvm-fresh");

        int firstClaim = jobRepository.claimForProcessing(job.getId(), "worker-A", FIVE_MINUTES_MICROS);
        assertThat(firstClaim).isEqualTo(1);

        int secondClaim = jobRepository.claimForProcessing(job.getId(), "worker-B", FIVE_MINUTES_MICROS);

        assertThat(secondClaim).isEqualTo(0);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-A");
    }

    @Test
    void stale_PROCESSING은_claimForProcessing으로_재선점되고_workerId와_processingStartedAt이_교체된다() {
        ProductAnalysisJob job = newJob("key-claim-stale-processing");
        forceProcessingStaleBy(job.getId(), "worker-old", 600);

        int updated = jobRepository.claimForProcessing(job.getId(), "worker-new", FIVE_MINUTES_MICROS);

        assertThat(updated).isEqualTo(1);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-new");
        // processing_started_at이 10분 전의 옛 값이 아니라 방금 claim 시점의 UTC_TIMESTAMP(6)로
        // 교체됐는지, DB의 UTC 시계 기준으로(JVM 시계와 비교하지 않고) 확인한다.
        assertThat(microsSinceProcessingStartedAtUtc(job.getId())).isLessThan(TimeUnit.SECONDS.toMicros(30));
    }

    // processing_started_at이 없던 Day4 이전에 생성됐을 legacy PROCESSING row 호환 정책: NULL은
    // staleAfterMicros 값과 무관하게 무조건 stale로 취급해 즉시 재선점된다. 이 정책은 "구버전
    // Worker가 모두 종료된 격리 환경"을 전제로 한다 - 구버전 Worker가 그 job을 아직 처리 중인
    // rolling deployment라면 살아있는 작업을 즉시 빼앗아 올 수 있다는 한계가 있다(ADR-04에서 다룸).
    @Test
    void processingStartedAt이_NULL인_PROCESSING은_staleAfterMicros와_무관하게_즉시_재선점된다() {
        ProductAnalysisJob job = newJob("key-claim-null-timestamp");
        forceProcessing(job.getId(), "worker-legacy", null);

        int updated = jobRepository.claimForProcessing(job.getId(), "worker-new", FIVE_MINUTES_MICROS);

        assertThat(updated).isEqualTo(1);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-new");
        assertThat(reloaded.getProcessingStartedAt()).isNotNull();
    }

    // 두 Worker가 같은 job을 동시에 최초 claim할 때 정확히 한쪽만 성공해야 한다. 클래스 레벨
    // @Transactional(테스트 종료 후 롤백)은 다른 커넥션/스레드에서 아직 커밋되지 않은 row를 볼 수
    // 없게 만들므로, 이 테스트만 NOT_SUPPORTED로 외부 트랜잭션 밖에서 실행하고 row를 실제 커밋한
    // 뒤 두 스레드를 CyclicBarrier로 동시에 풀어 경쟁시킨다. 결과는 우연한 타이밍이 아니라 MySQL의
    // row-level lock이 강제하는 결정적 결과다(둘 다 같은 row에 conditional UPDATE를 걸면 하나가
    // 먼저 lock을 잡고 commit해야 다른 하나가 재평가되어 반드시 0건이 된다).
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 동시_claim_경쟁하면_정확히_한쪽만_성공하고_최종_workerId가_승자와_일치한다() throws Exception {
        Long id = jobRepository.saveAndFlush(
                ProductAnalysisJob.create(1L, "uploads/race.jpg", "key-concurrent-claim")).getId();
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Map.Entry<String, Integer>>> tasks = List.of(
                        claimTask(id, "worker-A", barrier),
                        claimTask(id, "worker-B", barrier)
                );
                List<Future<Map.Entry<String, Integer>>> futures = pool.invokeAll(tasks, 10, TimeUnit.SECONDS);

                int totalAffected = 0;
                String winner = null;
                for (Future<Map.Entry<String, Integer>> future : futures) {
                    Map.Entry<String, Integer> outcome = future.get();
                    totalAffected += outcome.getValue();
                    if (outcome.getValue() == 1) {
                        winner = outcome.getKey();
                    }
                }

                assertThat(totalAffected).isEqualTo(1);
                assertThat(winner).isNotNull();

                ProductAnalysisJob finalJob = jobRepository.findById(id).orElseThrow();
                assertThat(finalJob.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
                assertThat(finalJob.getWorkerId()).isEqualTo(winner);
            } finally {
                pool.shutdownNow();
            }
        } finally {
            jdbcTemplate.update("delete from product_analysis_jobs where id = ?", id);
        }
    }

    private Callable<Map.Entry<String, Integer>> claimTask(Long id, String workerId, CyclicBarrier barrier) {
        return () -> {
            barrier.await(5, TimeUnit.SECONDS);
            int affected = jobRepository.claimForProcessing(id, workerId, FIVE_MINUTES_MICROS);
            return new AbstractMap.SimpleEntry<>(workerId, affected);
        };
    }

    // ---- Day4 completeIfOwned / failIfOwned: workerId fencing ----

    @Test
    void owner_일치시_completeIfOwned하면_1건_반영되고_COMPLETED가_된다() {
        ProductAnalysisJob job = newJob("key-complete-owner-match");
        forceProcessing(job.getId(), "worker-1", LocalDateTime.now());

        int updated = jobRepository.completeIfOwned(job.getId(), "worker-1");

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.COMPLETED);
    }

    @Test
    void owner_불일치시_completeIfOwned하면_0건_반영되고_PROCESSING이_유지된다() {
        ProductAnalysisJob job = newJob("key-complete-owner-mismatch");
        forceProcessing(job.getId(), "worker-1", LocalDateTime.now());

        int updated = jobRepository.completeIfOwned(job.getId(), "worker-2");

        assertThat(updated).isEqualTo(0);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-1");
    }

    @Test
    void owner_일치시_failIfOwned하면_1건_반영되고_FAILED가_된다() {
        ProductAnalysisJob job = newJob("key-fail-owner-match");
        forceProcessing(job.getId(), "worker-1", LocalDateTime.now());

        int updated = jobRepository.failIfOwned(job.getId(), "worker-1");

        assertThat(updated).isEqualTo(1);
        assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.FAILED);
    }

    @Test
    void owner_불일치시_failIfOwned하면_0건_반영되고_PROCESSING이_유지된다() {
        ProductAnalysisJob job = newJob("key-fail-owner-mismatch");
        forceProcessing(job.getId(), "worker-1", LocalDateTime.now());

        int updated = jobRepository.failIfOwned(job.getId(), "worker-2");

        assertThat(updated).isEqualTo(0);
        ProductAnalysisJob reloaded = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(AnalysisJobStatus.PROCESSING);
        assertThat(reloaded.getWorkerId()).isEqualTo("worker-1");
    }

    // ---- Day4 AnalysisResult: UNIQUE(analysis_id) + 최소 임시 payload ----

    // UNIQUE 위반 이후 같은 트랜잭션에서 추가 DB 작업을 하면 Spring이 rollback-only로 마킹한
    // 트랜잭션이라 후속 검증이 오염될 수 있다. NOT_SUPPORTED로 트랜잭션 밖에서 각 insert를 독립
    // 커밋시키고, 끝에서 수동으로 정리한다.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 같은_analysisId로_AnalysisResult_두건_INSERT하면_UNIQUE_위반으로_거부된다() {
        Long jobId = jobRepository.saveAndFlush(
                ProductAnalysisJob.create(1L, "uploads/result-a.jpg", "key-result-unique")).getId();
        try {
            analysisResultRepository.saveAndFlush(AnalysisResult.create(jobId, "{\"raw\":1}"));

            assertThatThrownBy(() ->
                    analysisResultRepository.saveAndFlush(AnalysisResult.create(jobId, "{\"raw\":2}"))
            ).isInstanceOf(DataIntegrityViolationException.class);

            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from analysis_result where analysis_id = ?", Integer.class, jobId);
            assertThat(count).isEqualTo(1);
        } finally {
            jdbcTemplate.update("delete from analysis_result where analysis_id = ?", jobId);
            jdbcTemplate.update("delete from product_analysis_jobs where id = ?", jobId);
        }
    }

    @Test
    void 다른_analysisId_AnalysisResult는_각각_저장된다() {
        AnalysisResult a = analysisResultRepository.saveAndFlush(AnalysisResult.create(101L, "result-a"));
        AnalysisResult b = analysisResultRepository.saveAndFlush(AnalysisResult.create(102L, "result-b"));

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(analysisResultRepository.findById(a.getId())).isPresent();
        assertThat(analysisResultRepository.findById(b.getId())).isPresent();
    }

    // rawResult는 VARCHAR(255) 기본값에 의존하면 안 된다 - AI 결과 JSON은 255자를 쉽게 넘긴다.
    // @Lob + columnDefinition=LONGTEXT로 실제 MySQL에 긴 문자열이 잘리지 않고 저장되는지 확인한다.
    @Test
    void 길이_255자를_넘는_rawResult도_LONGTEXT_컬럼에_그대로_저장된다() {
        String longPayload = "{\"padding\":\"" + "x".repeat(2000) + "\"}";

        AnalysisResult saved = analysisResultRepository.saveAndFlush(AnalysisResult.create(999L, longPayload));

        String reloaded = analysisResultRepository.findById(saved.getId()).orElseThrow().getRawResult();
        assertThat(reloaded).hasSize(longPayload.length());
        assertThat(reloaded).isEqualTo(longPayload);
    }
}
