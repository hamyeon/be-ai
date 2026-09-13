package com.vintic.backend.ai.vision.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 anthropic.* 값을 바인딩한다. ClaudeChatClient가 쓴다.
//
// api.key: ANTHROPIC_API_KEY. 비어 있으면 빈은 뜨되 첫 호출에서 AiApiException을 던진다 -
//          OpenAI만 쓰는 환경에서 키가 없다고 기동이 막히면 안 된다.
// max-retries: SDK 내장 재시도 횟수(429/5xx/연결 오류, retry-after 준수). OpenAI 클라이언트의
//          MAX_ATTEMPTS=5(최초 1 + 재시도 4)와 맞춘다.
// output-token-allowance: Claude의 max_tokens는 thinking 토큰까지 포함한다. 호출부가 넘기는
//          maxOutputTokens(900/1400)는 JSON 본문 기준이라, 그 값 그대로 보내면 생각하다 잘린다.
//          그래서 이 여유분을 더해 보낸다. OpenAI 쪽 의미(응답 길이 상한)는 그대로다.
// effort: output_config.effort(low/medium/high/xhigh/max). 비우면 API 기본값(high).
//          Vision 3단계는 분류에 가까운 작업이라 low/medium이 충분할 수 있는데, 그건 하네스로 잰다.
@Component
@ConfigurationProperties(prefix = "anthropic")
@Getter
@Setter
public class ClaudeClientProperties {

    private Api api = new Api();
    private int maxRetries = 4;
    private int outputTokenAllowance = 3000;
    private String effort;

    public boolean hasApiKey() {
        return api.getKey() != null && !api.getKey().isBlank();
    }

    @Getter
    @Setter
    public static class Api {
        private String key;
    }
}
