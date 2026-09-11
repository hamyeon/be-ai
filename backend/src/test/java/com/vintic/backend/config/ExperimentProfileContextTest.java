package com.vintic.backend.config;

import com.vintic.backend.BackendApplication;
import com.vintic.backend.analyze.job.SyncAnalysisController;
import com.vintic.backend.analyze.queue.RedisStreamConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// experiment-api/experiment-worker 프로필이 실제로 Controller(웹 계층)와 기존 Redis Streams
// Consumer를 의도한 대로 켜고 끄는지, 전체 애플리케이션을 직접 기동해 확인한다.
//
// @SpringBootTest(webEnvironment=...)를 쓰지 않는다 - 그 애너테이션은 webEnvironment 값에 따라
// spring.main.web-application-type을 테스트 하네스가 강제로 덮어써서, experiment-worker.yml이
// 설정한 web-application-type=none이 실제로 적용됐는지를 이 테스트가 구분할 수 없게 만든다.
// 대신 SpringApplicationBuilder로 실제 배포와 동일한 방식으로 기동하고 property 해석 결과를
// 그대로 관찰한다.
//
// datasource를 지정하지 않는 프로필(experiment-api/experiment-worker 단독)이므로 Spring Boot가
// 내장 H2로 자동 대체한다 - 실제 배포에서는 이 프로필들이 항상 local/dev/prod와 함께 켜져
// 그 프로필의 DB 설정을 쓰지만, 이 테스트는 프로필 자체의 격리 효과만 보는 것이므로 무관하다.
// analysis.job.queue.type은 InMemoryQueuePublisher로 명시해 "설정 없으면 기동 실패"라는
// 기존 fail-fast 규칙에 걸리지 않게 한다.
//
// Day5부터 이 두 프로필은 spring.flyway.enabled=true + ddl-auto=validate를 쓴다(V1/V2
// migration으로 실제 MySQL과 함께 켜졌을 때만 의미 있음). 여기서는 H2로 대체되는 격리
// 테스트라 MySQL 전용 migration SQL이 그대로 안 맞으므로 두 값을 다시 꺼서 이 테스트의
// 원래 목적(프로필별 빈 등록 여부)만 검증한다.
class ExperimentProfileContextTest {

    @Test
    void experiment_api는_웹_계층이_뜨고_기존_Redis_Consumer는_꺼진다() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-api")
                .run("--analysis.job.queue.type=in-memory", "--server.port=0",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(RedisStreamConsumerConfig.class)).isEmpty();
            assertThat(context.getBeansOfType(SyncAnalysisController.class)).hasSize(1);
        } finally {
            context.close();
        }
    }

    @Test
    void experiment_worker는_웹_계층이_뜨지_않고_기존_Redis_Consumer도_꺼진다() {
        // WorkerQueueTypeGuard가 analysis.job.queue.type=sqs를 요구하므로(그 값이 아니면
        // 기동 자체가 실패한다) in-memory 대신 sqs + 즉시 실패하는 로컬 endpoint를 쓴다 - 이
        // 테스트의 관심사는 실제 polling 성공이 아니라 "웹 계층/기존 Redis Consumer가 꺼지는가"
        // 뿐이다(실제 long polling 생존/graceful stop 검증은
        // SqsAnalysisJobPollerLifecycleLocalStackIT가 실제 LocalStack으로 따로 한다).
        ConfigurableApplicationContext context = new SpringApplicationBuilder(BackendApplication.class)
                .profiles("experiment-worker")
                .run("--analysis.job.queue.type=sqs",
                        "--analysis.job.queue.sqs.queue-url=http://localhost:1/000000000000/unused-queue",
                        "--analysis.job.queue.sqs.region=ap-northeast-2",
                        "--analysis.job.queue.sqs.endpoint-override=http://localhost:1",
                        "--spring.flyway.enabled=false", "--spring.jpa.hibernate.ddl-auto=update");

        try {
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeansOfType(RedisStreamConsumerConfig.class)).isEmpty();
            assertThat(context.containsBean(
                    "org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                    .isFalse();
            assertThat(context.getBeansOfType(SyncAnalysisController.class)).isEmpty();
        } finally {
            context.close();
        }
    }
}
