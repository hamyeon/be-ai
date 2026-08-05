package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplate;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import com.vintic.backend.ai.vision.dto.VisionUnreadable;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.common.exception.AiApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

// 한 번에 다 묻지 않고 3단계로 나눠 묻는 Vision 분석.
//
//   1단계 전체 형태  detail=low   실루엣/브랜드/모델 추정
//   2단계 라벨/로고  detail=high  사이즈/모델코드 판독  (1단계 결과를 맥락으로 받음)
//   3단계 오염/마모  detail=high  컨디션 등급 판정      (1,2단계 결과를 맥락으로 받음)
//
// 나눈 이유는 단계마다 필요한 게 다르기 때문이다. 실루엣은 512px로 줄여도 알아볼 수 있지만
// 텅 라벨의 작은 글자는 원본 해상도가 필요하다. 한 호출로 묶으면 전체를 비싼 쪽에 맞춰야 한다.
// 이미지를 세 번 보내는 만큼 비용이 늘어나는데, 그만한 값을 하는지는 하네스로 확인한다.
@Service
@Primary
@Slf4j
public class StagedVisionAnalysisService implements VisionAnalysisService {

    private static final String MODEL_NAME = "gpt-4o";
    private static final String PROMPT_CATEGORY = "vision";
    private static final String PROMPT_VERSION = "v2";

    private final OpenAiVisionClient visionClient;
    private final ObjectMapper objectMapper;
    private final VisionEvidenceValidator evidenceValidator;

    private final Stage silhouetteStage;
    private final Stage labelStage;
    private final Stage conditionStage;

    public StagedVisionAnalysisService(
            OpenAiVisionClient visionClient,
            ObjectMapper objectMapper,
            VisionEvidenceValidator evidenceValidator,
            PromptTemplateLoader promptTemplateLoader
    ) {
        this.visionClient = visionClient;
        this.objectMapper = objectMapper;
        this.evidenceValidator = evidenceValidator;

        // 프롬프트/스키마는 배포 중에 바뀌지 않으므로 기동 시 한 번만 읽어서 들고 있는다.
        this.silhouetteStage = loadStage(promptTemplateLoader, "silhouette", VisionImageDetail.LOW, 900);
        this.labelStage = loadStage(promptTemplateLoader, "label", VisionImageDetail.HIGH, 900);
        this.conditionStage = loadStage(promptTemplateLoader, "condition", VisionImageDetail.HIGH, 1400);
    }

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        List<String> imageUrls = request.imageUrls();
        log.info("Vision 분석 요청 - promptVersion={}, modelName={}, imageCount={}",
                PROMPT_VERSION, MODEL_NAME, imageUrls.size());

        SilhouetteStageResult silhouette = call(silhouetteStage, null, imageUrls, SilhouetteStageResult.class);
        LabelStageResult label = call(labelStage, contextOf("1단계(전체 형태) 결과", silhouette),
                imageUrls, LabelStageResult.class);
        ConditionStageResult condition = call(conditionStage,
                contextOf("1단계(전체 형태) 결과", silhouette) + contextOf("2단계(라벨/로고) 결과", label),
                imageUrls, ConditionStageResult.class);

        return evidenceValidator.enforce(merge(silhouette, label, condition), imageUrls.size());
    }

    private Stage loadStage(PromptTemplateLoader loader, String name, VisionImageDetail detail, int maxOutputTokens) {
        PromptTemplate template = loader.load(PROMPT_CATEGORY, name, PROMPT_VERSION);
        String schemaJson = loader.loadSchema(PROMPT_CATEGORY, name, PROMPT_VERSION);
        // json_schema.name은 영숫자와 밑줄만 허용된다.
        String schemaName = "vision_%s_%s".formatted(name.replace('-', '_'), PROMPT_VERSION);
        return new Stage(template, new VisionChatRequest.ResponseSchema(schemaName, schemaJson), detail, maxOutputTokens);
    }

    private <T> T call(Stage stage, String userText, List<String> imageUrls, Class<T> resultType) {
        VisionChatResponse response = visionClient.complete(new VisionChatRequest(
                MODEL_NAME,
                stage.template().content(),
                userText,
                imageUrls,
                stage.detail(),
                stage.responseSchema(),
                stage.maxOutputTokens()
        ));

        log.info("Vision 단계 완료 - stage={}, detail={}, promptTokens={}, completionTokens={}, latencyMs={}",
                stage.template().name(), stage.detail().value(),
                response.promptTokens(), response.completionTokens(), response.latencyMs());

        try {
            return objectMapper.readValue(response.content(), resultType);
        } catch (Exception e) {
            // Structured Outputs를 쓰므로 여기까지 오면 보통 응답이 잘렸거나 스키마 자체가 잘못된 경우다.
            log.error("Vision {} 단계 응답을 파싱하지 못했습니다. message={}", stage.template().name(), e.getMessage());
            throw new AiApiException("Vision 분석 응답을 처리하는 중 오류가 발생했습니다.", e);
        }
    }

    // 이전 단계 결과를 다음 단계의 맥락으로 넘긴다. 스키마 그대로의 JSON을 넘겨야 값이 왜곡되지 않는다.
    private String contextOf(String label, Object stageResult) {
        try {
            return "[%s]%n%s%n%n".formatted(label, objectMapper.writeValueAsString(stageResult));
        } catch (Exception e) {
            throw new AiApiException("Vision 단계 결과를 다음 단계 입력으로 변환하지 못했습니다.", e);
        }
    }

    private VisionAnalysisResult merge(
            SilhouetteStageResult silhouette, LabelStageResult label, ConditionStageResult condition) {

        // 라벨에서 읽어낸 값이 실루엣 추정보다 신뢰도가 높으므로 라벨 쪽을 우선한다.
        String brand = firstNonNull(label.brand(), silhouette.brand());
        String modelName = firstNonNull(label.modelName(), silhouette.modelName());

        List<VisionEvidence> evidence = new ArrayList<>();
        addAll(evidence, silhouette.evidence());
        addAll(evidence, label.evidence());
        addAll(evidence, condition.evidence());

        List<String> warnings = new ArrayList<>();
        addUnreadable(warnings, "1단계", silhouette.unreadable());
        addUnreadable(warnings, "2단계", label.unreadable());
        addUnreadable(warnings, "3단계", condition.unreadable());

        return new VisionAnalysisResult(
                brand,
                modelName,
                silhouette.color(),
                label.size(),
                condition.conditionDescription(),
                condition.conditionGrade() == null ? ConditionGrade.UNKNOWN : condition.conditionGrade(),
                label.boxIncluded(),
                condition.confidence(),
                condition.needsUserConfirmation(),
                List.copyOf(warnings),
                silhouette.candidates() == null ? List.of() : silhouette.candidates(),
                condition.defects() == null ? List.of() : condition.defects(),
                List.copyOf(evidence)
        );
    }

    private void addAll(List<VisionEvidence> target, List<VisionEvidence> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private void addUnreadable(List<String> warnings, String stageLabel, List<VisionUnreadable> unreadable) {
        if (unreadable == null) {
            return;
        }
        for (VisionUnreadable item : unreadable) {
            warnings.add("%s %s: %s".formatted(stageLabel, item.field(), item.reason()));
        }
    }

    private String firstNonNull(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private record Stage(
            PromptTemplate template,
            VisionChatRequest.ResponseSchema responseSchema,
            VisionImageDetail detail,
            int maxOutputTokens
    ) {
    }
}
