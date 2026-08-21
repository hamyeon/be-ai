package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.repository.AiCallLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 실제 쓰기만 담당한다. 예외를 삼키지 않고 그대로 던진다 - 삼키는 건 AiCallLogger의 몫이다.
//
// AiCallLogger에서 분리한 이유는 트랜잭션 프록시의 위치 때문이다. @Transactional을 붙인
// 메서드는 프록시가 감싸고, 커밋은 메서드가 반환된 뒤에 일어난다. 그래서 같은 메서드 안에서
// try-catch를 해도 커밋 시점의 예외는 잡히지 않고 호출부로 튀어나간다.
//
// 쓰기를 별도 빈으로 빼면 프록시 경계가 이 클래스에 생기고, AiCallLogger 입장에서는
// write() 호출 자체가 예외를 던지는 평범한 메서드가 된다. 그제서야 try-catch가 커밋 실패까지 덮는다.
@Component
@RequiredArgsConstructor
public class AiCallLogWriter {

    private final AiCallLogRepository aiCallLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AiCallLog callLog) {
        aiCallLogRepository.saveAndFlush(callLog);
    }
}
