package com.vintic.backend.ai.vision.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplate;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class OpenAiVisionAnalysisService implements VisionAnalysisService {

    private static final String PROMPT_CATEGORY = "vision";
    private static final String PROMPT_NAME = "product-analysis-system";
    private static final String PROMPT_VERSION = "v1";
    private static final String MODEL_NAME = "gpt-4o";

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper;
    private final PromptTemplate systemPromptTemplate;

    public OpenAiVisionAnalysisService(
            OpenAiService openAiService,
            ObjectMapper objectMapper,
            PromptTemplateLoader promptTemplateLoader
    ) {
        this.openAiService = openAiService;
        this.objectMapper = objectMapper;
        // 프롬프트 파일은 배포 중에는 바뀌지 않으므로 기동 시 한 번만 읽어서 들고 있는다.
        this.systemPromptTemplate = promptTemplateLoader.load(PROMPT_CATEGORY, PROMPT_NAME, PROMPT_VERSION);
    }

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        log.info(
                "Vision 분석 요청 - promptName={}, promptVersion={}, modelName={}",
                systemPromptTemplate.name(), systemPromptTemplate.version(), MODEL_NAME
        );

        String rawResult = openAiService.analyzeProductImages(request.imageUrls(), systemPromptTemplate.content());
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
