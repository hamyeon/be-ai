package com.vintic.backend.ai.vision.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Vision 3단계 분석이 주입받는 ChatCompletionClient 하나를 고른다.
//
// ChatCompletionClient 구현체가 둘(OpenAiVisionClient, ClaudeChatClient)이 되면서 타입만으로는
// 주입이 모호해졌다. 규칙은 이렇다:
//  - 이름 없는 ChatCompletionClient 주입(Goal 파서, Matcher)은 @Primary인 OpenAiVisionClient를 받는다.
//    그쪽 model 설정(gpt-4o-mini)이 OpenAI 기준이라, 벤더를 바꾸려면 그 기능의 provider 설정을
//    따로 열어야 한다(이번 범위 밖).
//  - Vision 분석은 "visionChatClient" 이름으로 주입받고, 이 빈이 vision.provider에 따라 둘 중 하나를 돌려준다.
@Configuration
@Slf4j
public class VisionClientConfig {

    public static final String VISION_CHAT_CLIENT = "visionChatClient";

    @Bean(VISION_CHAT_CLIENT)
    public ChatCompletionClient visionChatClient(VisionProviderProperties properties,
                                                 OpenAiVisionClient openAiVisionClient,
                                                 ClaudeChatClient claudeChatClient) {
        log.info("Vision provider={}, model={}", properties.getProvider(), properties.resolvedModel());
        return switch (properties.getProvider()) {
            case OPENAI -> openAiVisionClient;
            case CLAUDE -> claudeChatClient;
        };
    }
}
