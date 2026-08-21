package com.vintic.backend.ai.observability.domain;

// 호출이 실패한 이유의 분류.
//
// "실패했다"만 남기면 고칠 방법을 찾을 수 없다. API가 거절한 것과 응답은 왔는데
// 파싱이 안 된 것은 대응이 완전히 다르다. 전자는 재시도나 한도 조정이고,
// 후자는 프롬프트나 스키마를 손봐야 한다.
public enum AiCallFailureType {

    // OpenAI가 4xx/5xx로 거절했거나 네트워크 오류로 응답을 못 받음
    API_ERROR,

    // 응답은 받았는데 JSON으로 읽지 못함. 보통 응답이 잘렸거나 스키마가 잘못된 경우다.
    PARSE_ERROR,

    // 파싱은 됐지만 근거(evidence) 규칙 등 우리 쪽 검증을 통과하지 못함
    VALIDATION_ERROR
}
