package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.domain.AiCallFailureType;
import com.vintic.backend.ai.observability.domain.AiCallLog;
import com.vintic.backend.ai.observability.domain.AiCallType;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.observability.service.AiCallRequestSummary;
import com.vintic.backend.ai.prompt.PromptTemplate;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import com.vintic.backend.ai.vision.client.VisionClientConfig;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.dto.VisionEvidence;
import com.vintic.backend.ai.vision.dto.VisionProgress;
import com.vintic.backend.ai.vision.dto.VisionUnreadable;
import com.vintic.backend.ai.vision.image.VisionImageLoader;
import com.vintic.backend.ai.vision.image.VisionImageProperties;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import com.vintic.backend.ai.vision.service.VisionProgressListener;
import com.vintic.backend.common.exception.AiApiException;
import com.vintic.backend.common.exception.AiResponseFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

// 한 번에 다 묻지 않고 3단계로 나눠 묻는 Vision 분석.
//
//   1단계 전체 형태  detail=low   실루엣/브랜드/모델 추정
//   2단계 라벨/로고  detail=high  사이즈/모델코드 판독  (SEQUENTIAL이면 1단계 결과를 맥락으로 받음)
//   3단계 오염/마모  detail=high  컨디션 등급 판정      (SEQUENTIAL이면 1,2단계 결과를 맥락으로 받음)
//
// 앞 단계 맥락은 SEQUENTIAL에서만 전부 넘어간다(PARALLEL_LABEL_CONDITION은 1단계 결과만). 운영 기본값인
// ALL_PARALLEL은 앞 단계 맥락 없이 세 단계를 동시에 부른다(vision.stage.execution-mode).
//
// 나눈 이유는 단계마다 필요한 게 다르기 때문이다. 실루엣은 512px로 줄여도 알아볼 수 있지만
// 텅 라벨의 작은 글자는 더 높은 해상도가 필요하다(분석용 사본은 긴 변 768px로 줄여 보낸다, vision.image.max-edge).
// 한 호출로 묶으면 전체를 비싼 쪽에 맞춰야 한다.
// 이미지를 세 번 보내는 만큼 비용이 늘어나는데, 그만한 값을 하는지는 하네스로 확인한다.
//
// 단계별 실험 스위치(vision.stage.*.model / max-edge / max-images, vision.image.transport)를 켜면
// 단계마다 다른 모델·해상도·사진 장수로 부른다. 줄인 사본이나 base64가 필요하면 분석 시작 때 사진을
// 한 번 받아 단계들이 나눠 쓴다(VisionImageLoader). 전부 꺼져 있으면 사진을 받지 않고 URL을 그대로 넘긴다.
//
// 어느 벤더·모델을 부를지는 여기서 모른다. ChatCompletionClient는 VisionClientConfig가 vision.provider로
// 고른 빈이고, 모델명은 vision.model이다. 같은 코드로 OpenAI와 Claude를 하네스에서 비교하기 위해서다.
@Service
@Primary
@Slf4j
public class StagedVisionAnalysisService implements VisionAnalysisService {

    private static final String PROMPT_CATEGORY = "vision";
    private static final int TOTAL_STAGES = 3;
    private static final Map<String, String> UNREADABLE_FIELD_LABELS = Map.of(
            "brand", "브랜드",
            "modelName", "모델명",
            "color", "색상",
            "size", "사이즈",
            "sizeLabelText", "사이즈",
            "modelCode", "모델 코드",
            "boxIncluded", "박스 포함 여부",
            "conditionGrade", "상품 상태"
    );
    // 동시 실행이면 앞 단계 결과가 없다. 프롬프트는 "앞 단계 결과가 텍스트로 주어진다"고 말하므로
    // 빈 맥락으로 보내면 모델이 없는 결과를 찾거나 지어낼 수 있다. 없다고 명시한다.
    private static final String NO_PREVIOUS_STAGE_CONTEXT =
            "[앞 단계 결과]\n없음 - 이번 분석은 단계를 동시에 실행한다. 사진만 보고 판단한다.\n\n";

    // 어느 프롬프트 묶음을 쓰는지는 설정(vision.prompt-version)에서 온다(#106).
    private final String promptVersion;
    private final ChatCompletionClient visionClient;
    private final String modelName;
    private final VisionProviderProperties.Provider provider;
    private final ObjectMapper objectMapper;
    private final VisionEvidenceValidator evidenceValidator;
    private final AiCallLogger aiCallLogger;
    private final VisionStageProperties.ExecutionMode executionMode;
    private final Executor stageExecutor;
    private final VisionImageLoader imageLoader;
    private final VisionImageProperties.Transport imageTransport;

    private final Stage silhouetteStage;
    private final Stage labelStage;
    private final Stage conditionStage;

    public StagedVisionAnalysisService(
            @Qualifier(VisionClientConfig.VISION_CHAT_CLIENT) ChatCompletionClient visionClient,
            ObjectMapper objectMapper,
            VisionEvidenceValidator evidenceValidator,
            PromptTemplateLoader promptTemplateLoader,
            VisionStageProperties stageProperties,
            VisionProviderProperties providerProperties,
            AiCallLogger aiCallLogger,
            @Qualifier(VisionStageExecutorConfig.VISION_STAGE_EXECUTOR) Executor stageExecutor,
            VisionImageLoader imageLoader,
            VisionImageProperties imageProperties
    ) {
        this.visionClient = visionClient;
        this.modelName = providerProperties.resolvedModel();
        this.provider = providerProperties.getProvider();
        this.objectMapper = objectMapper;
        this.evidenceValidator = evidenceValidator;
        this.aiCallLogger = aiCallLogger;
        this.promptVersion = providerProperties.getPromptVersion();
        this.executionMode = stageProperties.getExecutionMode();
        this.stageExecutor = stageExecutor;
        this.imageLoader = imageLoader;
        this.imageTransport = imageProperties.getTransport();

        // 프롬프트/스키마는 배포 중에 바뀌지 않으므로 기동 시 한 번만 읽어서 들고 있는다.
        this.silhouetteStage = loadStage(promptTemplateLoader, "silhouette", stageProperties.getSilhouette());
        this.labelStage = loadStage(promptTemplateLoader, "label", stageProperties.getLabel());
        this.conditionStage = loadStage(promptTemplateLoader, "condition", stageProperties.getCondition());

        log.info("Vision 단계 설정 - model={}, promptVersion={}, silhouette={}, label={}, condition={}, executionMode={}, imageTransport={}",
                modelName, promptVersion, silhouetteStage.describe(), labelStage.describe(),
                conditionStage.describe(), executionMode, imageTransport);
    }

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
        return analyze(request, VisionProgressListener.NONE);
    }

    @Override
    public VisionAnalysisResult analyze(VisionAnalysisRequest request, VisionProgressListener progressListener) {
        List<String> imageUrls = request.imageUrls();
        Long analysisId = request.analysisId();
        log.info("Vision 분석 요청 - promptVersion={}, modelName={}, imageCount={}, executionMode={}",
                promptVersion, modelName, imageUrls.size(), executionMode);

        // 한 분석 안에서 진행 알림이 뒤로 가지 않게 한다. 동시 실행이면 2단계 알림이 1단계보다 먼저 나갈 수 있다.
        ProgressNotifier notifier = new ProgressNotifier(progressListener, imageUrls.size());

        StageImages images = prepareImages(imageUrls);

        StageResults results = switch (executionMode) {
            case SEQUENTIAL -> runSequentially(images, analysisId, notifier);
            case PARALLEL_LABEL_CONDITION -> runLabelAndConditionTogether(images, analysisId, notifier);
            case ALL_PARALLEL -> runAllTogether(images, analysisId, notifier);
        };

        return evidenceValidator.enforce(
                merge(results.silhouette(), results.label(), results.condition()), imageUrls.size());
    }

    private StageResults runSequentially(StageImages images, Long analysisId, ProgressNotifier notifier) {
        SilhouetteStageResult silhouette =
                call(silhouetteStage, null, images, analysisId, SilhouetteStageResult.class);
        notifier.stageCompleted(1, silhouette, null);

        LabelStageResult label = call(labelStage, contextOf("1단계(전체 형태) 결과", silhouette),
                images, analysisId, LabelStageResult.class);
        notifier.stageCompleted(2, silhouette, label);

        ConditionStageResult condition = call(conditionStage,
                contextOf("1단계(전체 형태) 결과", silhouette) + contextOf("2단계(라벨/로고) 결과", label),
                images, analysisId, ConditionStageResult.class);
        return new StageResults(silhouette, label, condition);
    }

    // 1단계 뒤에 2·3단계를 동시에 부른다(#106). 분석 한 건이 2단계 시간(약 3~4초)만큼 빨라지는 대신,
    // 3단계는 2단계가 읽어낸 라벨 값을 못 받고 1단계 결과만 맥락으로 받는다.
    private StageResults runLabelAndConditionTogether(StageImages images, Long analysisId, ProgressNotifier notifier) {
        SilhouetteStageResult silhouette =
                call(silhouetteStage, null, images, analysisId, SilhouetteStageResult.class);
        notifier.stageCompleted(1, silhouette, null);
        String silhouetteContext = contextOf("1단계(전체 형태) 결과", silhouette);

        CompletableFuture<LabelStageResult> labelFuture = CompletableFuture.supplyAsync(() -> {
            LabelStageResult label = call(labelStage, silhouetteContext, images, analysisId, LabelStageResult.class);
            notifier.stageCompleted(2, silhouette, label);
            return label;
        }, stageExecutor);
        CompletableFuture<ConditionStageResult> conditionFuture = CompletableFuture.supplyAsync(
                () -> call(conditionStage, silhouetteContext, images, analysisId, ConditionStageResult.class),
                stageExecutor);

        awaitAll(labelFuture, conditionFuture);
        return new StageResults(silhouette, join(labelFuture), join(conditionFuture));
    }

    // 세 단계를 한꺼번에 부른다(#106). 분석 시간이 세 단계의 합이 아니라 가장 느린 한 단계에 가까워진다.
    // 어느 단계도 앞 단계 결과를 맥락으로 받지 못한다. 합치는 규칙(라벨 우선, 없으면 1단계 값)은 그대로다.
    private StageResults runAllTogether(StageImages images, Long analysisId, ProgressNotifier notifier) {
        CompletableFuture<SilhouetteStageResult> silhouetteFuture = CompletableFuture.supplyAsync(
                () -> call(silhouetteStage, null, images, analysisId, SilhouetteStageResult.class), stageExecutor);
        CompletableFuture<LabelStageResult> labelFuture = CompletableFuture.supplyAsync(
                () -> call(labelStage, NO_PREVIOUS_STAGE_CONTEXT, images, analysisId, LabelStageResult.class), stageExecutor);
        CompletableFuture<ConditionStageResult> conditionFuture = CompletableFuture.supplyAsync(
                () -> call(conditionStage, NO_PREVIOUS_STAGE_CONTEXT, images, analysisId, ConditionStageResult.class), stageExecutor);

        // 잠정 결과는 1단계가 있어야 만들 수 있다. 라벨이 먼저 끝나면 1단계를 기다렸다가 2단계로 바로 알린다.
        silhouetteFuture.thenAccept(silhouette -> notifier.stageCompleted(1, silhouette, null));
        silhouetteFuture.thenAcceptBoth(labelFuture, (silhouette, label) -> notifier.stageCompleted(2, silhouette, label));

        awaitAll(silhouetteFuture, labelFuture, conditionFuture);
        return new StageResults(join(silhouetteFuture), join(labelFuture), join(conditionFuture));
    }

    // 하나가 실패해도 나머지가 끝날 때까지 기다린다. 먼저 예외를 던지면 남은 호출이 결과 없이 비용만 쓰고,
    // 그 호출 기록(AiCallLog)도 남지 않는다.
    private void awaitAll(CompletableFuture<?>... futures) {
        CompletableFuture.allOf(futures)
                .exceptionally(error -> null)
                .join();
    }

    private <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            // 호출부는 AiApiException/AiResponseFormatException을 기대한다. 감싼 예외를 벗겨 그대로 올린다.
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private record StageResults(SilhouetteStageResult silhouette, LabelStageResult label,
                                ConditionStageResult condition) {
    }

    // 분석 한 건의 진행 알림. 동시 실행이면 여러 스레드에서 불리므로 순서를 여기서 맞춘다 -
    // 이미 알린 단계 수보다 작거나 같은 알림은 버린다(2단계 알림 뒤에 1단계 알림이 오면 화면이 되돌아간다).
    private final class ProgressNotifier {

        private final VisionProgressListener listener;
        private final int imageCount;
        private int lastNotifiedStages;

        private ProgressNotifier(VisionProgressListener listener, int imageCount) {
            this.listener = listener;
            this.imageCount = imageCount;
        }

        synchronized void stageCompleted(int completedStages, SilhouetteStageResult silhouette, LabelStageResult label) {
            if (completedStages <= lastNotifiedStages) {
                return;
            }
            lastNotifiedStages = completedStages;
            notifyProgress(listener, completedStages, silhouette, label, imageCount);
        }
    }

    // 끝난 단계까지의 결과로 잠정값을 만들어 알린다(#106). 최종 결과와 같은 규칙을 쓴다 -
    // 라벨이 추정을 이기고, 근거 없는 값은 검증기가 지운다. 검증 전 값을 먼저 보여주면
    // 사용자는 나중에 사라질 브랜드를 보게 된다.
    private void notifyProgress(VisionProgressListener listener, int completedStages,
                                SilhouetteStageResult silhouette, LabelStageResult label, int imageCount) {
        if (listener == VisionProgressListener.NONE) {
            return;
        }
        try {
            List<VisionEvidence> evidence = new ArrayList<>();
            addAll(evidence, silhouette.evidence());
            String brand = silhouette.brand();
            String modelName = silhouette.modelName();
            Integer size = null;
            if (label != null) {
                addAll(evidence, label.evidence());
                brand = firstNonNull(label.brand(), brand);
                modelName = firstNonNull(label.modelName(), modelName);
                size = label.size();
            }

            VisionAnalysisResult validated = evidenceValidator.enforce(new VisionAnalysisResult(
                    brand, modelName, silhouette.color(), size, null, null, null, null, null,
                    List.of(), List.of(), List.of(), List.copyOf(evidence)), imageCount);

            listener.onStageCompleted(new VisionProgress(completedStages, TOTAL_STAGES,
                    validated.brand(), validated.modelName(), validated.color(), validated.size()));
        } catch (RuntimeException e) {
            // 진행 표시가 실패했다고 이미 비용을 낸 분석을 버리지 않는다.
            log.warn("Vision 진행 상황 전달 실패 - 분석은 계속합니다. stage={}, 원인: {}", completedStages, e.getMessage());
        }
    }

    // 단계 모델이 비었거나 지금 provider의 모델이 아니면 vision.model을 쓴다. 운영 기본값이 실루엣=Haiku라서,
    // VISION_PROVIDER=openai로 되돌릴 때 Claude 모델명이 OpenAI로 넘어가 모든 분석이 실패하는 일을 막는다.
    private String resolveStageModel(String stageName, String configured) {
        if (configured == null || configured.isBlank()) {
            return modelName;
        }
        String model = configured.trim();
        boolean claudeModel = model.startsWith("claude-");
        if (claudeModel != (provider == VisionProviderProperties.Provider.CLAUDE)) {
            log.warn("Vision {} 단계 모델 {}은 provider={}의 모델이 아니라 무시하고 {}을 씁니다.",
                    stageName, model, provider, modelName);
            return modelName;
        }
        return model;
    }

    private Stage loadStage(PromptTemplateLoader loader, String name, VisionStageProperties.Stage settings) {
        PromptTemplate template = loader.load(PROMPT_CATEGORY, name, promptVersion);
        String schemaJson = loader.loadSchema(PROMPT_CATEGORY, name, promptVersion);
        // json_schema.name은 영숫자와 밑줄만 허용된다.
        String schemaName = "vision_%s_%s".formatted(name.replace('-', '_'), promptVersion);
        String stageModel = resolveStageModel(name, settings.getModel());
        return new Stage(
                template,
                new VisionChatRequest.ResponseSchema(schemaName, schemaJson),
                settings.getDetail(),
                settings.getMaxOutputTokens(),
                stageModel,
                Math.max(0, settings.getMaxEdge()),
                Math.max(0, settings.getMaxImages())
        );
    }

    private <T> T call(Stage stage, String userText, StageImages images, Long analysisId, Class<T> resultType) {
        VisionChatRequest request = new VisionChatRequest(
                stage.model(),
                stage.template().content(),
                userText,
                images.of(stage),
                stage.detail(),
                stage.responseSchema(),
                stage.maxOutputTokens()
        );
        String requestSummary = AiCallRequestSummary.of(request, objectMapper);

        VisionChatResponse response;
        long startedAt = System.currentTimeMillis();
        try {
            response = visionClient.complete(request);
        } catch (RuntimeException e) {
            // API가 거절했거나 응답 자체를 못 받은 경우. 재시도를 모두 소진한 뒤 여기로 온다.
            recordFailure(stage, analysisId, requestSummary, AiCallFailureType.API_ERROR,
                    e.getMessage(), System.currentTimeMillis() - startedAt);
            throw e;
        }

        log.info("Vision 단계 완료 - stage={}, model={}, detail={}, promptTokens={}, completionTokens={}, latencyMs={}",
                stage.template().name(), stage.model(), stage.detail().value(),
                response.promptTokens(), response.completionTokens(), response.latencyMs());

        try {
            T parsed = objectMapper.readValue(response.content(), resultType);
            aiCallLogger.record(logBuilder(stage, analysisId, requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .build());
            return parsed;
        } catch (Exception e) {
            // Structured Outputs를 쓰므로 여기까지 오면 보통 응답이 잘렸거나 스키마 자체가 잘못된 경우다.
            log.error("Vision {} 단계 응답을 파싱하지 못했습니다. message={}", stage.template().name(), e.getMessage());
            // 파싱에 실패한 응답일수록 원문이 필요하다. 무엇이 왔는지 봐야 스키마를 고칠 수 있다.
            aiCallLogger.record(logBuilder(stage, analysisId, requestSummary)
                    .latencyMs(response.latencyMs())
                    .tokens(response.promptTokens(), response.completionTokens())
                    .responseBody(response.content())
                    .failure(AiCallFailureType.PARSE_ERROR, e.getMessage())
                    .build());
            throw new AiResponseFormatException("Vision 분석 응답을 처리하는 중 오류가 발생했습니다.", e);
        }
    }

    private void recordFailure(Stage stage, Long analysisId, String requestSummary,
                               AiCallFailureType failureType, String message, long latencyMs) {
        aiCallLogger.record(logBuilder(stage, analysisId, requestSummary)
                .latencyMs(latencyMs)
                .failure(failureType, message)
                .build());
    }

    private AiCallLog.Builder logBuilder(Stage stage, Long analysisId, String requestSummary) {
        return AiCallLog.builder(AiCallType.VISION, stage.model())
                .stage(stage.template().name())
                .promptVersion(promptVersion)
                .analysisId(analysisId)
                .requestSummary(requestSummary);
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
        addUnreadable(warnings, silhouette.unreadable());
        addUnreadable(warnings, label.unreadable());
        addUnreadable(warnings, condition.unreadable());

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

    // warnings는 화면에 그대로 나간다. 예전에는 "2단계 size: ..."처럼 단계 번호와 스키마 필드명을 붙였는데,
    // 사용자에게는 의미 없는 내부 표기라 뺀다. reason은 프롬프트가 사용자용 한국어(20자 이내)로 받는 문구다.
    // 필드명은 아는 것만 한국어로 바꿔 앞에 붙이고, 3단계의 부위명(outsole 등)처럼 모르는 것은 reason만 남긴다.
    // 단계를 동시에 돌리면 같은 사유가 여러 단계에서 올 수 있어 겹치는 문구는 한 번만 넣는다.
    private void addUnreadable(List<String> warnings, List<VisionUnreadable> unreadable) {
        if (unreadable == null) {
            return;
        }
        for (VisionUnreadable item : unreadable) {
            if (item == null || item.reason() == null || item.reason().isBlank()) {
                continue;
            }
            String label = UNREADABLE_FIELD_LABELS.get(item.field());
            String warning = label == null ? item.reason() : "%s: %s".formatted(label, item.reason());
            if (!warnings.contains(warning)) {
                warnings.add(warning);
            }
        }
    }

    private String firstNonNull(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private record Stage(
            PromptTemplate template,
            VisionChatRequest.ResponseSchema responseSchema,
            VisionImageDetail detail,
            int maxOutputTokens,
            String model,
            // 0이면 분석용 사본 그대로
            int maxEdge,
            // 0이면 전부
            int maxImages
    ) {

        String describe() {
            return "%s(model=%s, maxEdge=%d, maxImages=%d)".formatted(detail.value(), model, maxEdge, maxImages);
        }
    }

    // 사진을 받아야 하는지 정한다. base64 전달이거나 따로 줄여 보내는 단계가 있으면 받는다.
    // 받은 사진은 단계들이 나눠 쓰고, 같은 크기는 한 번만 줄인다.
    private StageImages prepareImages(List<String> imageUrls) {
        boolean base64 = imageTransport == VisionImageProperties.Transport.BASE64;
        boolean anyResize = silhouetteStage.maxEdge() > 0 || labelStage.maxEdge() > 0 || conditionStage.maxEdge() > 0;
        if (!base64 && !anyResize) {
            return new StageImages(imageUrls, null, false);
        }

        long startedAt = System.currentTimeMillis();
        List<VisionImageLoader.LoadedImage> loaded = imageLoader.load(imageUrls);
        log.info("Vision 분석용 사진 수신 - imageCount={}, latencyMs={}", imageUrls.size(), System.currentTimeMillis() - startedAt);
        return new StageImages(imageUrls, loaded, base64);
    }

    // 분석 한 건이 단계별로 보낼 사진. 동시 실행이면 여러 스레드가 부르므로 of()를 동기화한다.
    private final class StageImages {

        private final List<String> imageUrls;
        // null이면 사진을 받지 않았다 - 모든 단계가 URL을 그대로 쓴다.
        private final List<VisionImageLoader.LoadedImage> loaded;
        private final boolean base64;
        // 긴 변(0 = 그대로) -> 사진별 입력값. 같은 크기를 쓰는 단계끼리 인코딩을 나눠 쓴다.
        private final Map<Integer, List<String>> encodedByMaxEdge = new HashMap<>();

        private StageImages(List<String> imageUrls, List<VisionImageLoader.LoadedImage> loaded, boolean base64) {
            this.imageUrls = imageUrls;
            this.loaded = loaded;
            this.base64 = base64;
        }

        synchronized List<String> of(Stage stage) {
            List<String> inputs;
            if (loaded == null || (!base64 && stage.maxEdge() == 0)) {
                inputs = imageUrls;
            } else {
                inputs = encodedByMaxEdge.computeIfAbsent(stage.maxEdge(),
                        maxEdge -> loaded.stream().map(image -> imageLoader.toImageInput(image, maxEdge)).toList());
            }
            if (stage.maxImages() > 0 && inputs.size() > stage.maxImages()) {
                return inputs.subList(0, stage.maxImages());
            }
            return inputs;
        }
    }
}
