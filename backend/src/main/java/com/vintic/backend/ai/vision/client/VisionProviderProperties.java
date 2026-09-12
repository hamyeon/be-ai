package com.vintic.backend.ai.vision.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 vision.provider / vision.model 값을 바인딩한다.
//
// Vision 3단계 분석이 어느 벤더의 어느 모델을 부를지 정한다. 모델명이 코드 상수(gpt-4o)로 박혀
// 있으면 벤더를 바꿔 비교하려 할 때마다 재배포해야 하고, 하네스가 같은 코드로 두 벤더를 돌릴 수 없다.
// model을 비우면 provider별 기준 모델을 쓴다.
@Component
@ConfigurationProperties(prefix = "vision")
@Getter
@Setter
public class VisionProviderProperties {

    public enum Provider {
        OPENAI("gpt-4o"),
        CLAUDE("claude-opus-5");

        private final String defaultModel;

        Provider(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public String defaultModel() {
            return defaultModel;
        }
    }

    private Provider provider = Provider.OPENAI;
    private String model;

    public String resolvedModel() {
        return model == null || model.isBlank() ? provider.defaultModel() : model.trim();
    }
}
