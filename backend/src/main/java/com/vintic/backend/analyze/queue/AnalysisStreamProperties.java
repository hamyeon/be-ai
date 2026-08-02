package com.vintic.backend.analyze.queue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 analysis.stream.* 값을 바인딩한다. Stream 키/Consumer Group 이름을
// 코드에 하드코딩하지 않고 설정으로 분리해, 환경별로 바꾸거나 재배포 없이 조정할 수 있게 한다.
@Component
@ConfigurationProperties(prefix = "analysis.stream")
@Getter
@Setter
public class AnalysisStreamProperties {

    private String key = "ai:analysis:requests";
    private String group = "ai-analysis-workers";
    private String consumerPrefix = "worker";
}
