package com.vintic.backend.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpenAiVisionAnalysisService implements VisionAnalysisService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        String rawResult = openAiService.analyzeProductImages(request.imageUrls());
        return parse(rawResult);
    }

    private VisionAnalysisResult parse(String rawResult) {
        try {
            return objectMapper.readValue(rawResult, VisionAnalysisResult.class);
        } catch (JsonProcessingException e) {
            throw new AiApiException("Vision 분석 응답을 처리하는 중 오류가 발생했습니다.");
        }
    }
}
