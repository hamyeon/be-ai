package com.vintic.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    // @Primary 필수: visionRestTemplate 빈이 추가되면서 RestTemplate 타입 빈이 둘이 됐다.
    // OpenAiEmbeddingClient/KakaoUserInfoClient 등 @Qualifier 없이 타입으로만 주입받는 기존
    // 코드는 그대로 이 빈을 받아야 한다 - 아니면 컨텍스트 기동 자체가 애매한 주입으로 실패한다.
    @Bean
    @Primary
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // Vision(OpenAI) 호출 전용 RestTemplate. 위 restTemplate()은 OpenAiEmbeddingClient/
    // KakaoUserInfoClient도 함께 쓰는 공유 빈이라, 여기에 타임아웃을 걸면 그 호출들에도 영향을
    // 준다. Vision 큐의 PEL 회수(minIdleTime)가 "처리에 상한이 있다"는 전제로 동작하려면 Vision
    // 호출에만 명시적 타임아웃이 필요해서, 별도 빈으로 분리했다 - 다른 호출자는 이 값의 영향을
    // 받지 않는다.
    @Bean
    public RestTemplate visionRestTemplate(
            RestTemplateBuilder builder,
            @Value("${openai.vision.http.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${openai.vision.http.read-timeout-ms:30000}") long readTimeoutMs
    ) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
