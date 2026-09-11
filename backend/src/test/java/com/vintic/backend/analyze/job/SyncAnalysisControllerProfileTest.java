package com.vintic.backend.analyze.job;

import com.vintic.backend.analyze.job.processor.AnalysisProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// SyncAnalysisController(POST /api/analyses/sync)가 @Profile("experiment-api")일 때만
// 빈으로 등록되는지 - local/dev(프로필 없음)와 experiment-worker 조합에서는 등록되지 않는지를
// 웹 계층/실제 DB 없이 좁게 검증한다. ExperimentProfileContextTest(전체 부팅)와 겹치지 않게
// 이 테스트는 순수 프로필 조건만 본다.
class SyncAnalysisControllerProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SyncAnalysisController.class, SyncAnalysisService.class)
            .withBean(S3Client.class, () -> mock(S3Client.class))
            .withBean(AnalysisProcessor.class, () -> mock(AnalysisProcessor.class))
            .withPropertyValues("cloud.aws.s3.bucket=test-bucket");

    @Test
    void 활성_프로필이_없으면_sync_컨트롤러가_등록되지_않는다() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(SyncAnalysisController.class));
    }

    @Test
    void experiment_worker_프로필이면_sync_컨트롤러가_등록되지_않는다() {
        contextRunner.withPropertyValues("spring.profiles.active=experiment-worker")
                .run(context -> assertThat(context).doesNotHaveBean(SyncAnalysisController.class));
    }

    @Test
    void experiment_api_프로필이면_sync_컨트롤러가_등록된다() {
        contextRunner.withPropertyValues("spring.profiles.active=experiment-api")
                .run(context -> assertThat(context).hasSingleBean(SyncAnalysisController.class));
    }
}
