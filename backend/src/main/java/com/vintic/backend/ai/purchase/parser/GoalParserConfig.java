package com.vintic.backend.ai.purchase.parser;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 호출부가 주입받는 GoalParser 하나를 고른다.
//
// OPENAI: LLM 파서 + 실패 시 규칙 기반 fallback.
// RULE:   규칙 기반만. 하네스에서 LLM 우위가 확인되기 전이거나 OpenAI 장애 때 내리는 값.
//
// 두 구현체 모두 @Component라 GoalParser 타입 빈이 셋이 된다 - @Primary가 붙은 이 빈만
// 주입 대상이 되고, 나머지는 이름으로만 접근할 수 있다(하네스가 둘을 비교할 때 쓴다).
@Configuration
@Slf4j
public class GoalParserConfig {

    @Bean
    @Primary
    public GoalParser goalParser(GoalParserProperties properties,
                                 OpenAiGoalParser openAiGoalParser,
                                 RuleBasedGoalParser ruleBasedGoalParser) {
        log.info("Goal 파서 provider={}, model={}", properties.getProvider(), properties.getModel());
        return switch (properties.getProvider()) {
            case RULE -> ruleBasedGoalParser;
            case OPENAI -> new FallbackGoalParser(openAiGoalParser, ruleBasedGoalParser);
        };
    }
}
