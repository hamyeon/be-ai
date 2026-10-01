package com.vintic.backend.common.exception;

// 응답은 받았는데 약속한 형식이 아니어서 읽지 못한 경우.
//
// AiApiException을 상속하므로 잡는 쪽 동작은 그대로다. 따로 만든 이유는 "API가 거절함"과
// "형식이 어긋남"을 구분해야 대응이 갈리기 때문이다. 전자는 재시도·한도 조정이고
// 후자는 프롬프트나 스키마를 고쳐야 한다.
//
// 하네스는 이 구분으로 JSON 준수율을 낸다. 예외 메시지 문자열을 비교해 분류하면
// 메시지를 다듬는 순간 조용히 오분류되므로 타입으로 나눈다.
public class AiResponseFormatException extends AiApiException {

    public AiResponseFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
