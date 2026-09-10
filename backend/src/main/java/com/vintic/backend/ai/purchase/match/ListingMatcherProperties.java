package com.vintic.backend.ai.purchase.match;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 purchase-agent.matcher.* 값을 바인딩한다.
//
// provider: openai | rule. 파서와 달리 fallback 조합이 없다 - 실패는 후보 제외로 처리하기
// 때문이다. rule은 백엔드 루프 개발용 Fake이자 OpenAI 장애 시 대체 운영값이다.
@Component
@ConfigurationProperties(prefix = "purchase-agent.matcher")
@Getter
@Setter
public class ListingMatcherProperties {

    public enum Provider {
        OPENAI,
        RULE
    }

    private Provider provider = Provider.OPENAI;
    private String model = "gpt-4o-mini";
    private int maxOutputTokens = 300;
}
