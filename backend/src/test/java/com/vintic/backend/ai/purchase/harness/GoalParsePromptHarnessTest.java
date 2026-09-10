package com.vintic.backend.ai.purchase.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import com.vintic.backend.ai.purchase.parser.GoalDraftValidator;
import com.vintic.backend.ai.purchase.parser.GoalParserProperties;
import com.vintic.backend.ai.purchase.parser.OpenAiGoalParser;
import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 실제 OpenAI API를 불러 LLM Goal 파서의 정확도·지연·토큰을 재는 하네스.
 * OPENAI_API_KEY가 있고 -Dgoal.harness=true를 줘야 돈다. 평범한 ./gradlew test에는 딸려 들어가지 않는다.
 *
 * 실행 (PowerShell에서는 -D 인자를 따옴표로 감싼다):
 *   ./gradlew test --tests '*GoalParsePromptHarnessTest' -Dgoal.harness=true -Dgoal.harness.model=gpt-4o-mini
 *
 * 리포트는 build/goal-parse-harness/openai-{model}.txt에 남는다. 같은 픽스처로 돈
 * rule.txt(RuleBasedGoalParserHarnessTest)와 나란히 놓고 비교한다 - LLM이 규칙 기반보다
 * 나아야 provider=openai를 유지할 이유가 있다.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "goal.harness", matches = "true")
class GoalParsePromptHarnessTest {

    private static final String MODEL_PROPERTY = "goal.harness.model";
    private static final Path REPORT_DIRECTORY = Path.of("build", "goal-parse-harness");

    @Test
    void 픽스처_전체를_돌려_LLM_파서의_정확도와_비용을_측정한다() throws IOException {
        String model = System.getProperty(MODEL_PROPERTY, "gpt-4o-mini");
        GoalParseHarnessFixtures.Document fixtures = GoalParseHarnessFixtures.load();
        TokenCountingChatClient chatClient = new TokenCountingChatClient(createRealClient());
        OpenAiGoalParser parser = createParser(model, chatClient);

        List<GoalParseHarnessScorer.CaseScore> scores = new ArrayList<>();
        int caseCount = fixtures.cases().size();
        System.out.printf("[하네스] model=%s - %d건 시작%n", model, caseCount);
        for (int i = 0; i < caseCount; i++) {
            GoalParseHarnessCase harnessCase = fixtures.cases().get(i);
            long startedAt = System.currentTimeMillis();
            try {
                GoalDraft draft = parser.parse(harnessCase.text());
                scores.add(GoalParseHarnessScorer.score(harnessCase, draft, System.currentTimeMillis() - startedAt));
                System.out.printf("  [%d/%d] %s - %dms%n", i + 1, caseCount, harnessCase.id(), System.currentTimeMillis() - startedAt);
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 계속 재야 비교 가능한 표가 나온다.
                scores.add(GoalParseHarnessScorer.CaseScore.failed(harnessCase, System.currentTimeMillis() - startedAt, e));
                System.out.printf("  [%d/%d] %s - 실패: %s%n", i + 1, caseCount, harnessCase.id(), e.getMessage());
            }
        }

        GoalParseHarnessReport report = GoalParseHarnessReport.aggregate(
                "parser=openai, model=%s, fixtures=%s".formatted(model, fixtures.version()), scores, chatClient.usage());
        System.out.println(report.toText());
        Files.createDirectories(REPORT_DIRECTORY);
        Path reportPath = REPORT_DIRECTORY.resolve("openai-%s.txt".formatted(model));
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());
    }

    // @SpringBootTest가 아니라서 @Value("${openai.api.key}")가 주입되지 않는다. Vision 하네스와 같은 방식.
    private OpenAiVisionClient createRealClient() {
        OpenAiVisionClient client = new OpenAiVisionClient(new ObjectMapper(), new RestTemplate());
        ReflectionTestUtils.setField(client, "apiKey", System.getenv("OPENAI_API_KEY"));
        return client;
    }

    private OpenAiGoalParser createParser(String model, ChatCompletionClient chatClient) {
        GoalParserProperties properties = new GoalParserProperties();
        properties.setModel(model);
        ModelAliases modelAliases = new ModelAliases();
        return new OpenAiGoalParser(
                chatClient, new ObjectMapper(), new GoalDraftValidator(modelAliases),
                Mockito.mock(AiCallLogger.class), properties, new PromptTemplateLoader(), modelAliases);
    }

    // 실제 호출은 그대로 하면서 호출 횟수와 토큰만 누적한다. 정확도만 보고 모델을 고르면 비용을 모른 채 결정하게 된다.
    private static final class TokenCountingChatClient implements ChatCompletionClient {

        private final ChatCompletionClient delegate;
        private int apiCalls;
        private int promptTokens;
        private int completionTokens;

        private TokenCountingChatClient(ChatCompletionClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public VisionChatResponse complete(VisionChatRequest request) {
            VisionChatResponse response = delegate.complete(request);
            apiCalls++;
            promptTokens += response.promptTokens();
            completionTokens += response.completionTokens();
            return response;
        }

        GoalParseHarnessReport.Usage usage() {
            return new GoalParseHarnessReport.Usage(apiCalls, promptTokens, completionTokens);
        }
    }
}
