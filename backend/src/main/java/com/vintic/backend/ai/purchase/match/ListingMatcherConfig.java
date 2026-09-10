package com.vintic.backend.ai.purchase.match;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

// 호출부가 주입받는 ListingMatcher 하나를 고른다. 파서의 GoalParserConfig와 같은 구조.
@Configuration
@Slf4j
public class ListingMatcherConfig {

    @Bean
    @Primary
    public ListingMatcher listingMatcher(ListingMatcherProperties properties,
                                         OpenAiListingMatcher openAiListingMatcher,
                                         RuleBasedListingMatcher ruleBasedListingMatcher) {
        log.info("매물 적합도 Matcher provider={}, model={}", properties.getProvider(), properties.getModel());
        return switch (properties.getProvider()) {
            case RULE -> ruleBasedListingMatcher;
            case OPENAI -> openAiListingMatcher;
        };
    }
}
