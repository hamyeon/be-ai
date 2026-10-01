package com.vintic.backend.ai.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.client.VisionChatRequest;

import java.util.LinkedHashMap;
import java.util.Map;

// 호출 기록에 남길 요청 요약을 만든다.
//
// HttpEntity를 통째로 직렬화하지 않고 필요한 필드만 골라 담는다. 요청 헤더에는
// Authorization(API 키)이 들어 있어서, 전문을 남기는 습관을 들이면 언젠가 DB에 키가 저장된다.
// 여기서 만드는 Map에는 키가 닿지 않는다.
//
// 시스템 프롬프트 본문은 넣지 않는다. 매 호출마다 같은 값이 수 KB씩 반복 저장될 뿐이고,
// 어떤 프롬프트였는지는 promptVersion과 stage로 되짚을 수 있다.
public final class AiCallRequestSummary {

    private AiCallRequestSummary() {
    }

    public static String of(VisionChatRequest request, ObjectMapper objectMapper) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", request.model());
        summary.put("detail", request.detail().value());
        summary.put("maxOutputTokens", request.maxOutputTokens());
        summary.put("imageCount", request.imageUrls() == null ? 0 : request.imageUrls().size());
        summary.put("imageUrls", request.imageUrls());
        // 이전 단계 결과를 맥락으로 넘긴 텍스트. 단계 간 값이 어떻게 전달됐는지 보려면 필요하다.
        summary.put("userText", request.userText());
        summary.put("schemaName", request.responseSchema() == null ? null : request.responseSchema().name());
        return write(summary, objectMapper);
    }

    public static String ofEmbedding(String model, String input, ObjectMapper objectMapper) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", model);
        summary.put("input", input);
        return write(summary, objectMapper);
    }

    private static String write(Map<String, Object> summary, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            // 요약을 못 만든다고 호출을 실패시킬 이유는 없다. 기록만 비워둔다.
            return null;
        }
    }
}
