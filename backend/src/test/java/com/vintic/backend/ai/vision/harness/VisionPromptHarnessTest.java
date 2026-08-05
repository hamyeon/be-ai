package com.vintic.backend.ai.vision.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.vision.dto.VisionAnalysisRequest;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.ai.vision.service.OpenAiService;
import com.vintic.backend.ai.vision.service.OpenAiVisionAnalysisService;
import com.vintic.backend.ai.vision.service.VisionAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 실제 OpenAI Vision API를 호출해 지금 프롬프트가 어느 정도 성능인지 재는 하네스.
 * OPENAI_API_KEY가 설정된 환경에서만 실행된다. (CI에서는 자동으로 건너뛴다)
 *
 * 이 테스트는 통과/실패를 가르는 게 목적이 아니라 비교 가능한 수치를 남기는 게 목적이다.
 * 프롬프트나 호출 옵션을 바꿀 때마다 돌려서 build/vision-harness/에 쌓이는 리포트를 비교한다.
 *
 * 실행:
 *   ./gradlew test --tests '*VisionPromptHarnessTest' -Dvision.harness.variants=ORIGIN,THUMBNAIL_300
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class VisionPromptHarnessTest {

    private static final String VARIANTS_PROPERTY = "vision.harness.variants";
    private static final Path REPORT_DIRECTORY = Path.of("build", "vision-harness");

    @Test
    void 픽스처_전체를_돌려_필드별_정확도를_측정한다() throws IOException {
        VisionHarnessFixtures.Document fixtures = VisionHarnessFixtures.load();
        VisionAnalysisService visionAnalysisService = createVisionAnalysisService();

        for (VisionHarnessImageVariant variant : selectedVariants()) {
            List<VisionHarnessScorer.CaseScore> caseScores = new ArrayList<>();

            for (VisionHarnessCase harnessCase : fixtures.cases()) {
                List<String> imageUrls = variant.apply(harnessCase.imageBaseUrls());
                long startedAt = System.currentTimeMillis();
                try {
                    VisionAnalysisResult result = visionAnalysisService.analyze(new VisionAnalysisRequest(imageUrls));
                    caseScores.add(VisionHarnessScorer.score(harnessCase, result, elapsedSince(startedAt)));
                } catch (RuntimeException e) {
                    // 한 건이 실패해도 나머지 케이스는 계속 재야 비교 가능한 표가 나온다.
                    caseScores.add(VisionHarnessScorer.CaseScore.failed(
                            harnessCase.id(), elapsedSince(startedAt), e.getMessage()));
                }
            }

            String label = "prompt=product-analysis-system-v1, image=" + variant.name();
            VisionHarnessReport report = VisionHarnessReport.aggregate(label, caseScores);
            System.out.println(report.toText());
            writeReport(variant, report);
        }
    }

    private List<VisionHarnessImageVariant> selectedVariants() {
        String configured = System.getProperty(VARIANTS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return List.of(VisionHarnessImageVariant.ORIGIN);
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(name -> VisionHarnessImageVariant.valueOf(name.toUpperCase()))
                .toList();
    }

    // @SpringBootTest가 아니라서 @Value("${openai.api.key}")가 주입되지 않는다.
    // 임베딩 PoC 테스트와 같은 방식으로 환경변수에서 읽어 리플렉션으로 채운다.
    private VisionAnalysisService createVisionAnalysisService() {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiService openAiService = new OpenAiService(objectMapper);
        ReflectionTestUtils.setField(openAiService, "apiKey", System.getenv("OPENAI_API_KEY"));
        return new OpenAiVisionAnalysisService(openAiService, objectMapper, new PromptTemplateLoader());
    }

    private long elapsedSince(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    private void writeReport(VisionHarnessImageVariant variant, VisionHarnessReport report) throws IOException {
        Files.createDirectories(REPORT_DIRECTORY);
        Path reportPath = REPORT_DIRECTORY.resolve("v1-" + variant.name().toLowerCase() + ".txt");
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());
    }
}
