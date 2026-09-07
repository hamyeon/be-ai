package com.vintic.backend.analyze.job;

import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.job.queue.AnalysisJobQueueMessage;
import com.vintic.backend.analyze.job.queue.InMemoryQueuePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Day2 2단계 검증: ProductAnalysisJobService.submit()이 실제 MySQL(Testcontainers)과 InMemoryQueuePublisher를
// 통해 PENDING commit -> publish -> QUEUED 순서로 동작하는지, 동일 idempotencyKey의 순차/동시 요청에서
// job이 1건만 생기고 publish도 1회만 일어나는지 확인한다. AI(Vision) 서비스는 이 경로에서 호출되지
// 않아야 하므로 mock으로 대체하고 상호작용이 없는지도 함께 검증한다.
// 클래스 레벨 @Transactional을 쓰지 않는다 - submit()이 여러 트랜잭션에 걸쳐 커밋되는 것과
// 동시 스레드의 실제 커밋 가시성을 그대로 관찰해야 하기 때문이다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Testcontainers
class ProductAnalysisJobServiceMySqlIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private ProductAnalysisJobService jobService;

    @Autowired
    private ProductAnalysisJobRepository jobRepository;

    @Autowired
    private InMemoryQueuePublisher queuePublisher;

    @MockitoBean
    private VisionAnalysisService visionAnalysisService;

    private long countPublished(Long analysisId) {
        return queuePublisher.getPublished().stream()
                .filter(m -> m.equals(AnalysisJobQueueMessage.forJob(analysisId)))
                .count();
    }

    @Test
    void submit하면_PENDING_커밋_이후_publish되고_최종적으로_QUEUED가_된다() {
        ProductAnalysisJob result = jobService.submit(100L, "uploads/a.jpg", "seq-key-1");

        assertThat(result.getStatus()).isEqualTo(AnalysisJobStatus.QUEUED);
        assertThat(jobRepository.findById(result.getId()).orElseThrow().getStatus())
                .isEqualTo(AnalysisJobStatus.QUEUED);
        assertThat(countPublished(result.getId())).isEqualTo(1);
    }

    @Test
    void 같은_key로_순차_요청하면_기존_job을_반환하고_추가_publish가_없다() {
        ProductAnalysisJob first = jobService.submit(101L, "uploads/a.jpg", "seq-key-2");
        ProductAnalysisJob second = jobService.submit(101L, "uploads/b.jpg", "seq-key-2");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(countPublished(first.getId())).isEqualTo(1);
    }

    @Test
    void 같은_key로_동시_요청해도_job은_1건만_생기고_publish도_1회다() throws InterruptedException {
        Long userId = 102L;
        String idempotencyKey = "concurrent-key-1";
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<ProductAnalysisJob>> futures = List.of(
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return jobService.submit(userId, "uploads/a.jpg", idempotencyKey);
                    }),
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return jobService.submit(userId, "uploads/b.jpg", idempotencyKey);
                    })
            );

            ready.await();
            start.countDown();

            List<Long> resultIds = futures.stream()
                    .map(f -> {
                        try {
                            return f.get(10, TimeUnit.SECONDS).getId();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());

            assertThat(resultIds).containsOnly(resultIds.get(0));

            long dbCount = jobRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).stream().count();
            assertThat(dbCount).isEqualTo(1);
            assertThat(countPublished(resultIds.get(0))).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void submit_경로는_Vision_서비스를_호출하지_않는다() {
        jobService.submit(103L, "uploads/a.jpg", "no-ai-key");

        org.mockito.Mockito.verifyNoInteractions(visionAnalysisService);
    }
}
