package com.vintic.backend.ai.vision.agent;

import com.vintic.backend.ai.vision.client.VisionImageDetail;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// application.yml의 vision.stage.* 값이 실제로 바인딩되는지 확인한다.
//
// 설정 키 이름이 틀리거나 enum 변환이 안 되면 예외가 나는 게 아니라 조용히 기본값으로 떨어진다.
// 그러면 detail을 바꿔가며 측정해도 계속 같은 값으로 도는데 아무도 모른다.
class VisionStagePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(VisionStageProperties.class);

    @Test
    void 설정이_없으면_라벨_판독_단계부터_고해상도가_기본값이다() {
        contextRunner.run(context -> {
            VisionStageProperties properties = context.getBean(VisionStageProperties.class);

            assertThat(properties.getSilhouette().getDetail()).isEqualTo(VisionImageDetail.LOW);
            assertThat(properties.getLabel().getDetail()).isEqualTo(VisionImageDetail.HIGH);
            assertThat(properties.getCondition().getDetail()).isEqualTo(VisionImageDetail.HIGH);
        });
    }

    @Test
    void yml에_소문자로_적어도_해상도_설정이_바인딩된다() {
        // application.yml에는 detail: low 처럼 소문자로 적혀 있다.
        contextRunner
                .withPropertyValues(
                        "vision.stage.silhouette.detail=high",
                        "vision.stage.label.detail=low",
                        "vision.stage.condition.detail=auto")
                .run(context -> {
                    VisionStageProperties properties = context.getBean(VisionStageProperties.class);

                    assertThat(properties.getSilhouette().getDetail()).isEqualTo(VisionImageDetail.HIGH);
                    assertThat(properties.getLabel().getDetail()).isEqualTo(VisionImageDetail.LOW);
                    assertThat(properties.getCondition().getDetail()).isEqualTo(VisionImageDetail.AUTO);
                });
    }

    @Test
    void 응답_토큰_한도도_단계별로_바인딩된다() {
        contextRunner
                .withPropertyValues("vision.stage.condition.max-output-tokens=2500")
                .run(context -> {
                    VisionStageProperties properties = context.getBean(VisionStageProperties.class);

                    assertThat(properties.getCondition().getMaxOutputTokens()).isEqualTo(2500);
                    // 지정하지 않은 단계는 기본값을 유지한다
                    assertThat(properties.getLabel().getMaxOutputTokens()).isEqualTo(900);
                });
    }
}
