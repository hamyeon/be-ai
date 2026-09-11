package com.vintic.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

// 활성 프로필이 없으면 application.yml의 analysis.job.queue.type 기본값(빈 문자열)이 그대로
// 적용되어 QueuePublisher 빈이 없어 컨텍스트 로딩 자체가 실패한다 - 이건 실제 배포 환경(local/
// dev/experiment-*)에서는 항상 프로필이 지정되므로 절대 발생하지 않는 상황이고, 그 fail-fast
// 자체가 의도된 설계다(application.yml 주석 참고) - 그 정책은 건드리지 않는다. 이 테스트는
// Queue 동작을 전혀 검증하지 않고 "컨텍스트가 뜨는지"만 보므로, 부작용 없는 in-memory
// 구현으로 이 테스트 컨텍스트에서만 요구사항을 충족시킨다(전역 기본값을 바꾸지 않음).
@SpringBootTest
@TestPropertySource(properties = "analysis.job.queue.type=in-memory")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
