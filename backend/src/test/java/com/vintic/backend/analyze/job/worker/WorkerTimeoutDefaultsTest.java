package com.vintic.backend.analyze.job.worker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

// Day4 4단계 최종 검수: docs/infra-sprint/contracts.md의 Timeout 관계(Fake 11.164초 < AI timeout
// 60초 < 정상 처리 최대 70초 < shutdown wait 75초 < stale threshold 80초 < Visibility Timeout
// 90초)가 실제 @Value 기본값과 어긋나지 않는지 확인한다. 무거운 Spring 컨텍스트를 새로 띄우지
// 않고, 실제 생성자 파라미터의 @Value 애너테이션 문자열에서 기본값을 리플렉션으로 그대로
// 읽어온다 - 코드 기본값이 바뀌면 이 테스트도 함께 깨진다.
//
// AI timeout(60초)과 정상 처리 최대시간(70초)은 실제 구현이 없다(RealAnalysisProcessor는
// ADR-17에서 SKIPPED로 확정) - 코드가 강제하지 않는 문서상 관계값이므로 상수로만 둔다.
class WorkerTimeoutDefaultsTest {

    private static final double FAKE_DELAY_SECONDS = 11.164;
    private static final int AI_TIMEOUT_SECONDS = 60;
    private static final int NORMAL_MAX_PROCESSING_SECONDS = 70;

    @Test
    void 운영_기본값_timeout_관계가_contracts_문서와_일치한다() {
        long staleAfterSeconds = defaultLongValue(SqsAnalysisJobHandler.class, "analysis.worker.stale-after-seconds");
        long visibilityTimeoutSeconds = defaultLongValue(SqsAnalysisJobPoller.class, "analysis.worker.visibility-timeout-seconds");
        long shutdownTimeoutMs = defaultLongValue(SqsAnalysisJobPollerLifecycle.class, "analysis.worker.shutdown.timeout-ms");

        assertThat(staleAfterSeconds).isEqualTo(80L);
        assertThat(visibilityTimeoutSeconds).isEqualTo(90L);
        assertThat(shutdownTimeoutMs).isEqualTo(75_000L);

        double shutdownWaitSeconds = shutdownTimeoutMs / 1000.0;
        assertThat(FAKE_DELAY_SECONDS).isLessThan(AI_TIMEOUT_SECONDS);
        assertThat((double) AI_TIMEOUT_SECONDS).isLessThan(NORMAL_MAX_PROCESSING_SECONDS);
        assertThat((double) NORMAL_MAX_PROCESSING_SECONDS).isLessThan(shutdownWaitSeconds);
        assertThat(shutdownWaitSeconds)
                .as("shutdown wait는 stale threshold보다 짧아야 한다")
                .isLessThan(staleAfterSeconds);
        assertThat((double) staleAfterSeconds)
                .as("stale threshold는 Visibility Timeout보다 짧아야 한다(claim 지연을 감안해도 첫 재노출에서 stale 재선점이 가능해야 함)")
                .isLessThan(visibilityTimeoutSeconds);
    }

    @Test
    void maxReceiveCount_기본값은_3이다() {
        long maxReceiveCount = defaultLongValue(SqsAnalysisJobHandler.class, "analysis.worker.max-receive-count");
        assertThat(maxReceiveCount).isEqualTo(3L);
    }

    private long defaultLongValue(Class<?> targetClass, String propertyKey) {
        for (Constructor<?> constructor : targetClass.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null && value.value().startsWith("${" + propertyKey + ":")) {
                    String expr = value.value();
                    String defaultPart = expr.substring(expr.indexOf(':') + 1, expr.length() - 1);
                    return Long.parseLong(defaultPart);
                }
            }
        }
        throw new IllegalStateException(propertyKey + "에 대한 @Value 기본값을 찾지 못했습니다 - " + targetClass);
    }
}
