package com.vintic.backend.ai.purchase.parser;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 purchase-agent.parser.* 값을 바인딩한다.
//
// provider: 어느 구현을 쓸지. 하네스에서 LLM이 규칙 기반보다 낫다는 게 확인되기 전까지,
// 또는 OpenAI 장애 시 운영에서 규칙 기반으로 내릴 수 있게 설정값으로 뺐다.
// model/max-output-tokens: 파싱은 짧은 JSON 하나라 mini급으로 충분한지 하네스로 잰다.
@Component
@ConfigurationProperties(prefix = "purchase-agent.parser")
@Getter
@Setter
public class GoalParserProperties {

    public enum Provider {
        OPENAI,
        RULE
    }

    private Provider provider = Provider.OPENAI;
    private String model = "gpt-4o-mini";
    private int maxOutputTokens = 400;
}
