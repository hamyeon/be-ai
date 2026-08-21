package com.vintic.backend.ai.observability.domain;

// 어떤 종류의 AI API를 불렀는지.
//
// 엔드포인트가 다르면 비용 단가도 응답 형태도 다르다. 토큰 사용량을 집계할 때
// 섞이면 안 되므로 호출 종류를 남긴다.
public enum AiCallType {
    VISION,     // /v1/chat/completions - 이미지 분석
    EMBEDDING   // /v1/embeddings - 텍스트 임베딩
}
