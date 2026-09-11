package com.vintic.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled를 켠다.
//
// BackendApplication에 붙이지 않고 별도 설정으로 뺐다. 메인 클래스에 붙이면
// @SpringBootTest 계열이 전부 스케줄러를 끌고 들어와, 테스트 도중 배치가 돌 수 있다.
// 설정으로 두면 필요한 테스트만 골라 import 하거나 제외할 수 있다.
//
// experiment-worker/redis-baseline-test 프로필에서는 아예 끈다. 개별 스케줄러(payment/backup-offer/
// auction 등)는 이미 기본값이 꺼짐(application.yml)이라 dev를 함께 켜지 않는 한 영향이 없지만,
// SQS Worker/Redis baseline 실험 프로세스에서 트리거 등록조차 되지 않게 하는 최후 방어선으로
// 겹쳐 둔다. local/dev/prod는 그대로 영향받지 않는다.
@Configuration
@EnableScheduling
@Profile("!experiment-worker & !redis-baseline-test")
public class SchedulingConfig {
}
