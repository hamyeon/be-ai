package com.vintic.backend.ai.vision.client;

// "프롬프트 + (선택) 이미지 → Structured Outputs JSON 한 번"을 수행하는 클라이언트 계약.
//
// 요청/응답 값 객체(VisionChatRequest/VisionChatResponse)는 특정 벤더의 wire format을 담지
// 않는다 - 모델명, 시스템 프롬프트, 사용자 텍스트, 이미지 URL, 응답 JSON Schema, 토큰 사용량뿐이다.
// 그래서 호출부(Vision 3단계, Goal 파서, Matcher)는 이 인터페이스만 보고, OpenAI 전용 코드
// (chat/completions 본문 구조, choices[0].message, 429 재시도 힌트 파싱)는 구현체 안에 갇힌다.
//
// 구현체는 OpenAiVisionClient(@Primary - 이름 없이 주입받는 Goal 파서/Matcher가 쓴다)와
// ClaudeChatClient 둘이다. Vision 분석은 VisionClientConfig가 vision.provider로 둘 중 하나를 고른다.
// 응답 스키마 파일(*.schema.json)은 표준 JSON Schema라 두 벤더에 그대로 쓴다.
// 단, 프롬프트는 모델마다 반응이 달라 하네스로 다시 재야 한다.
public interface ChatCompletionClient {

    VisionChatResponse complete(VisionChatRequest request);
}
