package com.vintic.backend.ai.vision.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// vision.provider가 실제로 Vision 분석의 클라이언트를 바꾸는지, 그리고 그 선택이 이름 없이
// ChatCompletionClient를 주입받는 다른 곳(Goal 파서, Matcher)에 새어 나가지 않는지 고정한다.
class VisionClientConfigTest {

    @Configuration
    static class Support {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withPropertyValues("openai.api.key=test-openai")
            .withUserConfiguration(Support.class, VisionProviderProperties.class, ClaudeClientProperties.class,
                    OpenAiVisionClient.class, ClaudeChatClient.class, VisionClientConfig.class);

    @Test
    void 설정이_없으면_OpenAI가_기본이고_모델은_gpt_4o다() {
        contextRunner.run(context -> {
            ChatCompletionClient visionClient = context.getBean(VisionClientConfig.VISION_CHAT_CLIENT, ChatCompletionClient.class);
            assertThat(visionClient).isInstanceOf(OpenAiVisionClient.class);
            assertThat(context.getBean(VisionProviderProperties.class).resolvedModel()).isEqualTo("gpt-4o");
        });
    }

    @Test
    void provider를_claude로_두면_Vision만_Claude_클라이언트를_받고_기본_주입은_여전히_OpenAI다() {
        contextRunner
                .withPropertyValues("vision.provider=claude")
                .run(context -> {
                    ChatCompletionClient visionClient = context.getBean(VisionClientConfig.VISION_CHAT_CLIENT, ChatCompletionClient.class);
                    assertThat(visionClient).isInstanceOf(ClaudeChatClient.class);
                    assertThat(context.getBean(VisionProviderProperties.class).resolvedModel()).isEqualTo("claude-opus-5");

                    // @Primary - Goal 파서·Matcher처럼 이름 없이 주입받는 곳은 계속 OpenAI를 받는다.
                    assertThat(context.getBean(ChatCompletionClient.class)).isInstanceOf(OpenAiVisionClient.class);
                });
    }

    @Test
    void 모델을_지정하면_provider_기본_모델을_덮어쓴다() {
        contextRunner
                .withPropertyValues("vision.provider=claude", "vision.model= claude-sonnet-5 ")
                .run(context -> assertThat(context.getBean(VisionProviderProperties.class).resolvedModel())
                        .isEqualTo("claude-sonnet-5"));
    }

    @Test
    void Claude_키가_없어도_컨텍스트는_뜬다() {
        // OpenAI만 쓰는 환경에서 ANTHROPIC_API_KEY 부재가 기동 실패가 되면 안 된다.
        contextRunner
                .withPropertyValues("anthropic.api.key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ClaudeClientProperties.class).hasApiKey()).isFalse();
                });
    }
}
