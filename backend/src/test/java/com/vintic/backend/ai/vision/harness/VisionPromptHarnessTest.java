package com.vintic.backend.ai.vision.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.agent.StagedVisionAnalysisService;
import com.vintic.backend.ai.vision.agent.VisionEvidenceValidator;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.OpenAiVisionAnalysisService;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * 실제 OpenAI Vision API를 호출해 프롬프트 성능을 재는 하네스.
 * OPENAI_API_KEY가 설정된 환경에서만 실행된다. (CI에서는 자동으로 건너뛴다)
 *
 * 통과/실패를 가르는 게 목적이 아니라 비교 가능한 수치를 남기는 게 목적이다.
 * 프롬프트나 호출 옵션을 바꿀 때마다 돌려서 build/vision-harness/에 쌓이는 리포트를 비교한다.
 *
 * 실행:
 *   ./gradlew test --tests '*VisionPromptHarnessTest' -Dvision.harness=true \
 *     -Dvision.harness.agents=V1,V2 -Dvision.harness.variants=ORIGIN,THUMBNAIL_300
 *
 * agents  V1 = 한 번에 다 묻는 기존 방식, V2 = 3단계로 나눈 방식 (기본값: 둘 다)
 * variants ORIGIN = 원본 해상도, THUMBNAIL_300 = 크롤러가 저장한 300x300 (기본값: ORIGIN)
 *
 * 키가 있는 것만으로는 실행되지 않고 -Dvision.harness=true를 줘야 돈다.
 * 평가 셋 한 바퀴가 유료 호출 수십 번이라, 평범한 ./gradlew test에 딸려 들어가면 안 된다.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "vision.harness", matches = "true")
class VisionPromptHarnessTest {

    private static final String AGENTS_PROPERTY = "vision.harness.agents";
    private static final String VARIANTS_PROPERTY = "vision.harness.variants";
    private static final Path REPORT_DIRECTORY = Path.of("build", "vision-harness");

    private enum Agent {
        V1, V2
    }

    @Test
    void 픽스처_전체를_돌려_필드별_정확도와_비용을_측정한다() throws IOException {
        VisionHarnessFixtures.Document fixtures = VisionHarnessFixtures.load();
        TokenCountingVisionClient visionClient = createVisionClient();

        for (Agent agent : selected(AGENTS_PROPERTY, Agent::valueOf, List.of(Agent.V1, Agent.V2))) {
            VisionAnalysisService service = createService(agent, visionClient);

            for (VisionHarnessImageVariant variant : selected(
                    VARIANTS_PROPERTY, VisionHarnessImageVariant::valueOf, List.of(VisionHarnessImageVariant.ORIGIN))) {

                visionClient.reset();
                List<VisionHarnessScorer.CaseScore> caseScores = new ArrayList<>();

                for (VisionHarnessCase harnessCase : fixtures.cases()) {
                    List<String> imageUrls = variant.apply(harnessCase.imageBaseUrls());
                    long startedAt = System.currentTimeMillis();
                    try {
                        VisionAnalysisResult result = service.analyze(new VisionAnalysisRequest(imageUrls));
                        caseScores.add(VisionHarnessScorer.score(harnessCase, result, elapsedSince(startedAt)));
                    } catch (RuntimeException e) {
                        // 한 건이 실패해도 나머지는 계속 재야 비교 가능한 표가 나온다.
                        caseScores.add(VisionHarnessScorer.CaseScore.failed(
                                harnessCase.id(), elapsedSince(startedAt), e.getMessage()));
                    }
                }

                String label = "agent=%s, image=%s".formatted(agent, variant);
                VisionHarnessReport report = VisionHarnessReport.aggregate(label, caseScores, visionClient.usage());
                System.out.println(report.toText());
                writeReport(agent, variant, report);
            }
        }
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

    // @SpringBootTest가 아니라서 @Value("${openai.api.key}")가 주입되지 않는다.
    // 임베딩 PoC 테스트와 같은 방식으로 환경변수에서 읽어 리플렉션으로 채운다.
    private TokenCountingVisionClient createVisionClient() {
        TokenCountingVisionClient client = new TokenCountingVisionClient(new ObjectMapper(), new RestTemplate());
        ReflectionTestUtils.setField(client, "apiKey", System.getenv("OPENAI_API_KEY"));
        return client;
    }

    private VisionAnalysisService createService(Agent agent, TokenCountingVisionClient visionClient) {
        ObjectMapper objectMapper = new ObjectMapper();
        PromptTemplateLoader promptTemplateLoader = new PromptTemplateLoader();
        return switch (agent) {
            case V1 -> new OpenAiVisionAnalysisService(visionClient, objectMapper, promptTemplateLoader);
            case V2 -> new StagedVisionAnalysisService(
                    visionClient, objectMapper, new VisionEvidenceValidator(), promptTemplateLoader);
        };
    }

    private long elapsedSince(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private void writeReport(Agent agent, VisionHarnessImageVariant variant, VisionHarnessReport report)
            throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        Path reportPath = REPORT_DIRECTORY.resolve(
                "%s-%s.txt".formatted(agent.name().toLowerCase(), variant.name().toLowerCase()));
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());
    }
}
