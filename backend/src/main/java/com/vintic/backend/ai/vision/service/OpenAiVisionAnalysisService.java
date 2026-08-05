package com.vintic.backend.ai.vision.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplate;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 한 번의 호출로 모든 필드를 묻는 v1 방식.
//
// 지금 서비스가 실제로 쓰는 건 3단계로 나눈 StagedVisionAnalysisService(@Primary)다.
// 이 구현은 "나누기 전"과 비교하기 위한 기준선으로 남겨둔다 - 하네스가 두 구현을 같은 평가 셋에
// 돌려 수치를 비교할 수 있어야, 단계를 나눈 게 실제로 나은지 판단할 수 있다.
@Service
@Slf4j
public class OpenAiVisionAnalysisService implements VisionAnalysisService {

    private static final String PROMPT_CATEGORY = "vision";
    private static final String PROMPT_NAME = "product-analysis-system";
    private static final String PROMPT_VERSION = "v1";
    private static final String MODEL_NAME = "gpt-4o";
    private static final int MAX_OUTPUT_TOKENS = 1000;

    private final OpenAiVisionClient visionClient;
    private final ObjectMapper objectMapper;
    private final PromptTemplate systemPromptTemplate;

    public OpenAiVisionAnalysisService(
            OpenAiVisionClient visionClient,
            ObjectMapper objectMapper,
            PromptTemplateLoader promptTemplateLoader
    ) {
        this.visionClient = visionClient;
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

        VisionChatResponse response = visionClient.complete(new VisionChatRequest(
                MODEL_NAME,
                systemPromptTemplate.content(),
                null,
                request.imageUrls(),
                VisionImageDetail.AUTO,
                null,   // v1은 json_object만 쓰고 스키마를 고정하지 않는다
                MAX_OUTPUT_TOKENS
        ));

        return parse(response.content());
    }

    private VisionAnalysisResult parse(String rawResult) {
        try {
            return objectMapper.readValue(rawResult, VisionAnalysisResult.class);
        } catch (Exception e) {
            throw new AiApiException("Vision 분석 응답을 처리하는 중 오류가 발생했습니다.", e);
        }
    }
}
