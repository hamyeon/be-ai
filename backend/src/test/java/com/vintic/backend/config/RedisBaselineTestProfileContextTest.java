package com.vintic.backend.config;

import com.vintic.backend.BackendApplication;
import com.vintic.backend.ai.vision.service.FakeVisionAnalysisService;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.analyze.queue.RedisStreamConsumerConfig;
import com.vintic.backend.analyze.job.worker.SqsAnalysisJobPoller;
import com.vintic.backend.analyze.job.worker.SqsAnalysisJobPollerLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

// redis-baseline-test의 실제 기동 계약은 단독이 아니라 local,redis-baseline-test 조합이다
// (application-redis-baseline-test.yml 참고 - local처럼 실제 DB/Redis 연결 정보를 아는
// 프로필과 함께 켜서 그 프로필의 analysis.job.queue.type=in-memory를 그대로 재사용한다).
// 그래서 이 테스트도 그 조합으로만 기동하고, QueuePublisher 설정 누락을 명령줄 오버라이드나
// mock으로 가리지 않는다 - local이 이미 제공하는 실제 설정이 그대로 작동하는지를 본다.
//
// local 프로필은 실제 MySQL 연결 정보를 요구하므로(application-local.yml),
// ProductAnalysisJobRepositoryMySqlIT와 동일하게 Testcontainers MySQL을 띄워 그 접속 정보를
// spring.datasource.* 커맨드라인 인자로 넘긴다. @DynamicPropertySource는 쓰지 않는다 -
// @SpringBootTest 없이 SpringApplicationBuilder로 직접 기동해야 redis-baseline-test.yml의
// web-application-type: none이 테스트 하네스가 아니라 프로필 자신의 설정으로 적용됐는지
// 구분할 수 있기 때문이다(ExperimentProfileContextTest와 같은 이유).
//
// Redis는 이 저장소의 다른 local 프로필 기반 테스트들과 마찬가지로 실제로 연결 가능한
// 로컬 Redis(spring.data.redis.host/port 기본값)에 의존한다 - Testcontainers Redis를
// 새로 추가하지 않는다.
@Testcontainers
class RedisBaselineTestProfileContextTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test
    void local_redis_baseline_test_조합은_기존_Redis_Consumer만_켜지고_SQS_Consumer와_웹_계층_스케줄러는_꺼진다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("local", "redis-baseline-test")
                .run(
                        "--spring.datasource.url=" + mysql.getJdbcUrl(),
                        "--spring.datasource.username=" + mysql.getUsername(),
                        "--spring.datasource.password=" + mysql.getPassword()
                );

        try {
            assertThat(context.getBeansOfType(RedisStreamConsumerConfig.class)).hasSize(1);
            assertThat(context.getBeansOfType(SqsAnalysisJobPoller.class)).isEmpty();
            assertThat(context.getBeansOfType(SqsAnalysisJobPollerLifecycle.class)).isEmpty();
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .isFalse();
            assertThat(context.getBean(VisionAnalysisService.class)).isInstanceOf(FakeVisionAnalysisService.class);
        } finally {
            context.close();
        }
    }
}
