package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.agent.StagedVisionAnalysisService;
import com.vintic.backend.ai.vision.agent.VisionEvidenceValidator;
import com.vintic.backend.ai.vision.agent.VisionStageProperties;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.ClaudeChatClient;
import com.vintic.backend.ai.vision.client.ClaudeClientProperties;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionImageDetail;
import com.vintic.backend.ai.vision.client.VisionProviderProperties;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.image.ImageResizer;
import com.vintic.backend.ai.vision.image.VisionImageLoader;
import com.vintic.backend.ai.vision.image.VisionImageProperties;
import com.vintic.backend.ai.vision.service.OpenAiVisionAnalysisService;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 실제 Vision API(OpenAI 또는 Claude)를 호출해 프롬프트 성능을 재는 하네스.
 * 선택한 provider의 API 키(OPENAI_API_KEY / ANTHROPIC_API_KEY)가 있는 환경에서만 실행된다. (CI에서는 자동으로 건너뛴다)
 *
 * 통과/실패를 가르는 게 목적이 아니라 비교 가능한 수치를 남기는 게 목적이다.
 * 프롬프트나 호출 옵션을 바꿀 때마다 돌려서 build/vision-harness/에 쌓이는 리포트를 비교한다.
 * 리포트(.txt)에는 단계별 평균 지연·토큰이, 옆의 -calls.csv에는 호출 한 번당 한 줄씩 원자료가 남는다.
 *
 * 실행 (PowerShell에서는 -D 인자를 따옴표로 감싸야 한다):
 *   ./gradlew test --tests '*VisionPromptHarnessTest' -Dvision.harness=true \
 *     -Dvision.harness.fixtures=fruitsfamily -Dvision.harness.agents=V1,V2
 *   ./gradlew test --tests '*VisionPromptHarnessTest' -Dvision.harness=true \
 *     -Dvision.harness.provider=claude -Dvision.harness.model=claude-sonnet-5
 *
 * provider openai | claude (기본값: openai). 같은 픽스처·프롬프트·스키마로 벤더를 비교한다.
 * model    provider별 기준 모델(gpt-4o / claude-opus-5)을 덮어쓴다. 리포트 파일명에 들어간다.
 * effort   Claude 전용. output_config.effort(low/medium/high). 비우면 API 기본값.
 * fixtures daangn = 이미지 1장, 해상도 A/B 가능 / fruitsfamily = 여러 장, 사이즈 판독 측정 가능
 *          (기본값: daangn)
 * agents   V1 = 한 번에 다 묻는 기존 방식, V2 = 3단계로 나눈 방식 (기본값: 둘 다)
 * variants ORIGIN = 원본 해상도, THUMBNAIL_300 = 크롤러가 저장한 300x300 (기본값: ORIGIN)
 *          daangn 셋에서만 의미가 있다
 * prompt-version 프롬프트 묶음 버전(기본 v2). v3 = 출력을 줄인 판(근거는 짧은 영어, 설명 길이 제한).
 *          응답 생성 시간이 출력 토큰 수에 비례하므로 분석 시간을 줄일 후보다.
 * execution 단계 실행 방식: sequential(기본) | parallel_label_condition | all_parallel.
 *          동시 실행은 분석 시간이 줄어드는 대신 뒤 단계가 앞 단계 결과를 못 받는다 - 정확도가 유지되는지 본다.
 * image-transport url(기본) | base64. base64면 서버가 사진을 한 번 받아 요청에 실어 보낸다(받는 시간 포함해 잰다).
 * {stage}-model / {stage}-max-edge / {stage}-max-images  stage = silhouette | label | condition.
 *          단계별 모델, 그 단계만 줄여 보낼 긴 변(px), 앞에서부터 보낼 사진 장수. 예:
 *          -Dvision.harness.silhouette-model=claude-haiku-5-5 -Dvision.harness.silhouette-max-edge=512
 *          설정한 것만 리포트 이름과 라벨에 붙는다.
 * detail   OpenAI 전용. Claude에는 대응 파라미터가 없어 원본 해상도로 간다 - 벤더를 공정하게 비교하려면
 *          OpenAI 쪽을 -Dvision.harness.detail=high로 맞춘다.
 *
 * 키가 있는 것만으로는 실행되지 않고 -Dvision.harness=true를 줘야 돈다.
 * 평가 셋 한 바퀴가 유료 호출 수십 번이라, 평범한 ./gradlew test에 딸려 들어가면 안 된다.
 */
@EnabledIfSystemProperty(named = "vision.harness", matches = "true")
class VisionPromptHarnessTest {

    private static final String PROVIDER_PROPERTY = "vision.harness.provider";
    private static final String MODEL_PROPERTY = "vision.harness.model";
    private static final String EFFORT_PROPERTY = "vision.harness.effort";
    private static final String FIXTURES_PROPERTY = "vision.harness.fixtures";
    private static final String AGENTS_PROPERTY = "vision.harness.agents";
    private static final String VARIANTS_PROPERTY = "vision.harness.variants";
    private static final String DETAIL_PROPERTY = "vision.harness.detail";
    private static final String EXECUTION_PROPERTY = "vision.harness.execution";
    private static final String PROMPT_VERSION_PROPERTY = "vision.harness.prompt-version";
    private static final String IMAGE_TRANSPORT_PROPERTY = "vision.harness.image-transport";
    private static final List<String> STAGES = List.of("silhouette", "label", "condition");
    private static final Path REPORT_DIRECTORY = Path.of("build", "vision-harness");

    private enum Agent {
        V1, V2
    }

    @Test
    void 픽스처_전체를_돌려_필드별_정확도와_비용을_측정한다() throws IOException {
        VisionProviderProperties providerProperties = providerProperties();
        String fixtureSet = System.getProperty(FIXTURES_PROPERTY, VisionHarnessFixtures.DAANGN);
        VisionHarnessFixtures.Document fixtures = VisionHarnessFixtures.load(fixtureSet);
        TokenCountingVisionClient visionClient = createVisionClient(providerProperties);

        for (Agent agent : selected(AGENTS_PROPERTY, Agent::valueOf, List.of(Agent.V1, Agent.V2))) {
            VisionAnalysisService service = createService(agent, visionClient, providerProperties);

            for (VisionHarnessImageVariant variant : variantsFor(fixtures)) {

                visionClient.reset();
                List<VisionHarnessScorer.CaseScore> caseScores = new ArrayList<>();
                Map<String, List<VisionHarnessReport.Call>> callsByCase = new LinkedHashMap<>();
                int caseCount = fixtures.cases().size();

                System.out.printf("[하네스] provider=%s model=%s agent=%s image=%s - %d건 시작%n",
                        providerProperties.getProvider(), providerProperties.resolvedModel(), agent, variant, caseCount);

                for (int i = 0; i < caseCount; i++) {
                    VisionHarnessCase harnessCase = fixtures.cases().get(i);
                    List<String> imageUrls = variant.apply(harnessCase.imageBaseUrls());
                    int firstCallIndex = visionClient.callCount();
                    long startedAt = System.currentTimeMillis();
                    try {
                        VisionAnalysisResult result = service.analyze(new VisionAnalysisRequest(imageUrls));
                        caseScores.add(VisionHarnessScorer.score(harnessCase, result, elapsedSince(startedAt)));
                        // 케이스마다 수십 초씩 걸려서, 진행 표시가 없으면 멈춘 것처럼 보인다.
                        System.out.printf("  [%d/%d] %s - %dms%n",
                                i + 1, caseCount, harnessCase.id(), elapsedSince(startedAt));
                    } catch (RuntimeException e) {
                        // 한 건이 실패해도 나머지는 계속 재야 비교 가능한 표가 나온다.
                        caseScores.add(VisionHarnessScorer.CaseScore.failed(
                                harnessCase.id(), elapsedSince(startedAt), e));
                        System.out.printf("  [%d/%d] %s - 실패: %s%n",
                                i + 1, caseCount, harnessCase.id(), e.getMessage());
                    }
                    // 실패한 케이스도 실패 전까지 성공한 단계는 남긴다(예: 1단계 성공 후 2단계에서 429).
                    callsByCase.put(harnessCase.id(), visionClient.callsSince(firstCallIndex));
                }

                String detailLabel = System.getProperty(DETAIL_PROPERTY, "기본(low/high/high)");
                String label = "provider=%s, model=%s, prompt=%s, set=%s, agent=%s, image=%s, detail=%s, execution=%s%s"
                        .formatted(providerProperties.getProvider(), providerProperties.resolvedModel(),
                                providerProperties.getPromptVersion(), fixtureSet, agent, variant, detailLabel,
                                executionMode(), experimentOptions().isEmpty() ? "" : ", " + String.join(", ", experimentOptions()));
                VisionHarnessReport report = VisionHarnessReport.aggregate(
                        label, caseScores, visionClient.usage(), callsByCase);
                System.out.println(report.toText());
                writeReport(providerProperties, fixtureSet, agent, variant, report);
            }
        }
    }

    // 해상도를 바꿔 붙일 수 없는 셋에 변형을 요청하면, 같은 이미지를 두 번 재고 다른 결과인 것처럼
    // 표가 두 장 나온다. 조용히 그러지 않도록 걸러내고 무엇을 건너뛰었는지 알린다.
    private List<VisionHarnessImageVariant> variantsFor(VisionHarnessFixtures.Document fixtures) {
        List<VisionHarnessImageVariant> requested = selected(
                VARIANTS_PROPERTY, VisionHarnessImageVariant::valueOf, List.of(VisionHarnessImageVariant.ORIGIN));

        if (fixtures.allowsImageVariants()) {
            return requested;
        }
        if (requested.size() > 1 || !requested.contains(VisionHarnessImageVariant.ORIGIN)) {
            System.out.println("[하네스] 이 평가 셋은 해상도 변형을 지원하지 않아 ORIGIN만 실행합니다. 요청됨: " + requested);
        }
        return List.of(VisionHarnessImageVariant.ORIGIN);
    }

    private <T> List<T> selected(String property, Function<String, T> parser, List<T> defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> parser.apply(value.toUpperCase()))
                .toList();
    }

    private VisionProviderProperties providerProperties() {
        VisionProviderProperties properties = new VisionProviderProperties();
        String provider = System.getProperty(PROVIDER_PROPERTY);
        if (provider != null && !provider.isBlank()) {
            properties.setProvider(VisionProviderProperties.Provider.valueOf(provider.trim().toUpperCase()));
        }
        properties.setModel(System.getProperty(MODEL_PROPERTY));
        String promptVersion = System.getProperty(PROMPT_VERSION_PROPERTY);
        if (promptVersion != null && !promptVersion.isBlank()) {
            properties.setPromptVersion(promptVersion.trim());
        }
        return properties;
    }

    // @SpringBootTest가 아니라서 @Value / @ConfigurationProperties가 주입되지 않는다.
    // 임베딩 PoC 테스트와 같은 방식으로 환경변수에서 읽어 직접 채운다.
    // 선택한 provider의 키가 없으면 실패가 아니라 건너뛴다 - 다른 벤더 키만 있는 환경에서 빨간불이 나면 안 된다.
    private TokenCountingVisionClient createVisionClient(VisionProviderProperties providerProperties) {
        ChatCompletionClient client = switch (providerProperties.getProvider()) {
            case OPENAI -> {
                String apiKey = System.getenv("OPENAI_API_KEY");
                Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY가 없어 하네스를 건너뜁니다.");
                OpenAiVisionClient openAi = new OpenAiVisionClient(new ObjectMapper(), new RestTemplate());
                ReflectionTestUtils.setField(openAi, "apiKey", apiKey);
                yield openAi;
            }
            case CLAUDE -> {
                String apiKey = System.getenv("ANTHROPIC_API_KEY");
                Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ANTHROPIC_API_KEY가 없어 하네스를 건너뜁니다.");
                ClaudeClientProperties claudeProperties = new ClaudeClientProperties();
                claudeProperties.getApi().setKey(apiKey);
                claudeProperties.setEffort(System.getProperty(EFFORT_PROPERTY));
                yield new ClaudeChatClient(new ObjectMapper(), claudeProperties);
            }
        };
        return new TokenCountingVisionClient(client);
    }

    private VisionAnalysisService createService(Agent agent, TokenCountingVisionClient visionClient,
                                                VisionProviderProperties providerProperties) {
        ObjectMapper objectMapper = new ObjectMapper();
        PromptTemplateLoader promptTemplateLoader = new PromptTemplateLoader();
        return switch (agent) {
            case V1 -> new OpenAiVisionAnalysisService(visionClient, objectMapper, promptTemplateLoader, providerProperties);
            case V2 -> new StagedVisionAnalysisService(
                    visionClient, objectMapper, new VisionEvidenceValidator(), promptTemplateLoader,
                    stageProperties(), providerProperties, org.mockito.Mockito.mock(AiCallLogger.class),
                    // 동시 실행을 잴 때 실제로 겹쳐서 돌아야 하므로 진짜 스레드를 쓴다. ALL_PARALLEL이 3개를 쓴다.
                    Executors.newFixedThreadPool(3),
                    new VisionImageLoader(new ImageResizer()), imageProperties());
        };
    }

    // -Dvision.harness.detail=low 를 주면 모든 단계를 그 해상도로 맞춘다.
    // 지정하지 않으면 application.yml의 기본값(1단계 low, 2·3단계 high)과 같은 조합으로 돈다.
    private VisionStageProperties stageProperties() {
        VisionStageProperties properties = new VisionStageProperties();
        properties.setExecutionMode(executionMode());
        applyStageOptions("silhouette", properties.getSilhouette());
        applyStageOptions("label", properties.getLabel());
        applyStageOptions("condition", properties.getCondition());
        String configured = System.getProperty(DETAIL_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return properties;
        }
        VisionImageDetail detail = VisionImageDetail.valueOf(configured.trim().toUpperCase());
        properties.getSilhouette().setDetail(detail);
        properties.getLabel().setDetail(detail);
        properties.getCondition().setDetail(detail);
        return properties;
    }

    // -Dvision.harness.{stage}-model / -max-edge / -max-images
    private void applyStageOptions(String stage, VisionStageProperties.Stage settings) {
        String model = stageOption(stage, "model");
        if (model != null) {
            settings.setModel(model);
        }
        String maxEdge = stageOption(stage, "max-edge");
        if (maxEdge != null) {
            settings.setMaxEdge(Integer.parseInt(maxEdge));
        }
        String maxImages = stageOption(stage, "max-images");
        if (maxImages != null) {
            settings.setMaxImages(Integer.parseInt(maxImages));
        }
    }

    private String stageOption(String stage, String option) {
        String value = System.getProperty("vision.harness.%s-%s".formatted(stage, option));
        return value == null || value.isBlank() ? null : value.trim();
    }

    private VisionImageProperties imageProperties() {
        VisionImageProperties properties = new VisionImageProperties();
        String transport = System.getProperty(IMAGE_TRANSPORT_PROPERTY);
        if (transport != null && !transport.isBlank()) {
            properties.setTransport(VisionImageProperties.Transport.valueOf(transport.trim().toUpperCase()));
        }
        return properties;
    }

    // 실험 옵션 중 설정한 것만 "silhouette-model=claude-haiku-5-5" 꼴로 모은다. 리포트 라벨과 파일명에 쓴다.
    private List<String> experimentOptions() {
        List<String> options = new ArrayList<>();
        String transport = System.getProperty(IMAGE_TRANSPORT_PROPERTY);
        if (transport != null && !transport.isBlank()) {
            options.add("transport=" + transport.trim().toLowerCase());
        }
        for (String stage : STAGES) {
            for (String option : List.of("model", "max-edge", "max-images")) {
                String value = stageOption(stage, option);
                if (value != null) {
                    options.add("%s-%s=%s".formatted(stage, option, value));
                }
            }
        }
        return options;
    }

    // -Dvision.harness.execution=sequential | parallel_label_condition | all_parallel (대소문자·하이픈 무관)
    private VisionStageProperties.ExecutionMode executionMode() {
        String configured = System.getProperty(EXECUTION_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return VisionStageProperties.ExecutionMode.SEQUENTIAL;
        }
        return VisionStageProperties.ExecutionMode.valueOf(configured.trim().toUpperCase().replace('-', '_'));
    }

    private long elapsedSince(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private void writeReport(VisionProviderProperties providerProperties, String fixtureSet, Agent agent,
                             VisionHarnessImageVariant variant, VisionHarnessReport report) throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        // detail·모델을 파일명에 넣지 않으면 low/high, OpenAI/Claude 실행이 서로를 덮어써서 비교할 게 남지 않는다.
        // 기존 OpenAI gpt-4o 리포트 이름은 그대로 유지한다(과거 리포트와 이어 붙여 볼 수 있게).
        String detailSuffix = System.getProperty(DETAIL_PROPERTY, "default").toLowerCase();
        boolean legacyOpenAi = providerProperties.getProvider() == VisionProviderProperties.Provider.OPENAI
                && "gpt-4o".equals(providerProperties.resolvedModel());
        String modelPrefix = legacyOpenAi ? "" : providerProperties.resolvedModel().toLowerCase() + "-";
        // 순차/동시 실행 리포트가 서로를 덮어쓰면 비교할 게 남지 않는다.
        String executionSuffix = executionMode() == VisionStageProperties.ExecutionMode.SEQUENTIAL
                ? "" : "-" + executionMode().name().toLowerCase();
        // 프롬프트 v2 리포트 이름은 그대로 두고(과거 리포트와 이어 볼 수 있게), v3부터 이름에 붙인다.
        String promptSuffix = "v2".equals(providerProperties.getPromptVersion())
                ? "" : "-prompt_" + providerProperties.getPromptVersion();
        // 실험 옵션도 이름에 붙인다. 붙이지 않으면 Haiku/512px 회차가 기준 회차 리포트를 덮어쓴다.
        String experimentSuffix = experimentOptions().stream()
                .map(option -> "-" + option.replace('=', '_').replace("max-", "max"))
                .reduce("", String::concat);
        String baseName = "%s%s-%s-%s-detail_%s%s%s%s".formatted(
                modelPrefix, fixtureSet, agent.name().toLowerCase(), variant.name().toLowerCase(),
                detailSuffix, promptSuffix, executionSuffix, experimentSuffix);
        Path reportPath = REPORT_DIRECTORY.resolve(baseName + ".txt");
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());

        Path callsPath = REPORT_DIRECTORY.resolve(baseName + "-calls.csv");
        Files.writeString(callsPath, report.toCallsCsv(), StandardCharsets.UTF_8);
        System.out.println("호출 원자료 저장: " + callsPath.toAbsolutePath());
    }
}
