package com.vintic.backend.ai.observability.service;

import com.vintic.backend.ai.observability.domain.AiCallLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// AI 호출 기록을 남긴다.
//
// 이 클래스의 계약: record()는 어떤 경우에도 예외를 던지지 않는다.
//
// 관측을 위해 얹은 부가 작업이 본래 기능을 망가뜨리면 안 된다. #49에서 상품 벡터 저장
// 실패가 상품 등록 전체를 롤백시킨 적이 있는데, 원인은 예외를 잡아도 같은 트랜잭션이
// rollback-only로 표시되기 때문이었다. 여기서도 같은 구조라 같은 방식으로 막는다.
//
//   - 쓰기를 AiCallLogWriter로 분리해 REQUIRES_NEW 트랜잭션을 따로 연다
//   - 그 덕에 커밋 시점의 예외도 아래 try-catch 안에서 잡힌다
//   - Throwable까지 잡는다. 컬럼 길이 초과처럼 Error 계열로 올라오는 경우가 있다.
@Service
@RequiredArgsConstructor
@Slf4j
public class AiCallLogger {

    private final AiCallLogWriter aiCallLogWriter;

    public void record(AiCallLog callLog) {
        try {
            aiCallLogWriter.write(callLog);
        } catch (Throwable e) {
            // 기록 실패는 로그로만 남기고 삼킨다. 이걸 던지면 AI 분석이 통째로 실패한다.
            log.warn("AI 호출 기록에 실패했습니다. callType={}, stage={}, message={}",
                    callLog.getCallType(), callLog.getStage(), e.getMessage());
        }
    }
}
