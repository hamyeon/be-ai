package com.vintic.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled를 켠다.
//
// BackendApplication에 붙이지 않고 별도 설정으로 뺐다. 메인 클래스에 붙이면
// @SpringBootTest 계열이 전부 스케줄러를 끌고 들어와, 테스트 도중 배치가 돌 수 있다.
// 설정으로 두면 필요한 테스트만 골라 import 하거나 제외할 수 있다.
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
