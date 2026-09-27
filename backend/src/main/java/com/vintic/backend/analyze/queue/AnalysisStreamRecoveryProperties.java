package com.vintic.backend.analyze.queue;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// PEL(pending entries list) 회수(XPENDING + XCLAIM) 설정.
// minIdleTimeMs 기본값(240s)은 analysis.vision.overall-timeout-ms(180s, Vision 처리 강제 상한)
// + 여유시간(claim DB 왕복 30s + 스케줄러 지연/시계 오차 30s)으로 도출한 값이다 - 근거 없이
// 잡은 값이 아니다. 이 값보다 오래 걸리는 정상 처리는 analysis.vision.overall-timeout-ms가
// 먼저 강제로 끊으므로, "살아있는데 회수당하는" 오탐은 이 설계상 발생하지 않는다.
// enabled 기본값을 false로 두는 이유는 이 프로젝트의 다른 opt-in 스케줄러(AuctionEndScheduler 등)
// 와 동일하다 - 다수의 *MySqlIT가 application-local 프로필을 공유하는데, 기본 활성화하면 그
// 테스트들의 @SpringBootTest 컨텍스트에서도 세션을 회수/변경해버릴 수 있다. 실제 배포 프로필
// (application-dev.yml)에서 명시적으로 켠다.
@Component
@ConfigurationProperties(prefix = "analysis.stream.recovery")
@Getter
@Setter
public class AnalysisStreamRecoveryProperties {

    private boolean enabled = false;
    private long minIdleTimeMs = 240_000L;
    private int batchSize = 20;
    private long scanIntervalMs = 30_000L;
}
