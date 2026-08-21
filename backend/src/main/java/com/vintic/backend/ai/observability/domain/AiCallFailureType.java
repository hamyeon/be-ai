package com.vintic.backend.ai.observability.domain;

// 호출이 실패한 이유의 분류.
//
// "실패했다"만 남기면 고칠 방법을 찾을 수 없다. API가 거절한 것과 응답은 왔는데
// 파싱이 안 된 것은 대응이 완전히 다르다. 전자는 재시도나 한도 조정이고,
// 후자는 프롬프트나 스키마를 손봐야 한다.
//
// "근거 검증 실패"는 넣지 않았다. VisionEvidenceValidator는 예외를 던지지 않고
// 근거 없는 값을 걷어낸 뒤 결과를 돌려주므로, 그건 호출 실패가 아니라 정상 응답이다.
// 검증이 값을 얼마나 걷어냈는지를 남기려면 별도 지표가 필요하다.
public enum AiCallFailureType {

    // OpenAI가 4xx/5xx로 거절했거나 네트워크 오류로 응답을 못 받음
    API_ERROR,

    // 응답은 받았는데 JSON으로 읽지 못함. 보통 응답이 잘렸거나 스키마가 잘못된 경우다.
    PARSE_ERROR
}
