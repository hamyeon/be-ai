package com.vintic.backend.ai.vision.client;

import java.util.List;

// OpenAI Chat Completions(Vision) 호출 한 번에 필요한 입력.
//
// 단계별로 모델/해상도/응답 스키마가 달라지므로 호출 옵션을 값 객체로 묶어 클라이언트에 넘긴다.
public record VisionChatRequest(
        String model,
        String systemPrompt,
        // 이전 단계 결과를 다음 단계 입력으로 넘길 때 쓴다. 없으면 이미지만 보낸다.
        String userText,
        List<String> imageUrls,
        VisionImageDetail detail,
        // null이면 느슨한 json_object 모드로 호출한다. 값이 있으면 Structured Outputs(strict)로 고정한다.
        ResponseSchema responseSchema,
        int maxOutputTokens
) {

    public record ResponseSchema(String name, String schemaJson) {
    }
}
