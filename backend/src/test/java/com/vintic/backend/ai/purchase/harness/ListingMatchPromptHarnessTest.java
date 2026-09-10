package com.vintic.backend.ai.purchase.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.observability.service.AiCallLogger;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import com.vintic.backend.ai.purchase.match.ListingMatcherProperties;
import com.vintic.backend.ai.purchase.match.MatchResult;
import com.vintic.backend.ai.purchase.match.MatchResultValidator;
import com.vintic.backend.ai.purchase.match.OpenAiListingMatcher;
import com.vintic.backend.ai.purchase.model.ModelAliases;
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
 * 실제 OpenAI API를 불러 LLM Matcher의 정확도·거짓 양성·지연·토큰을 재는 하네스.
 * OPENAI_API_KEY가 있고 -Dgoal.harness=true를 줘야 돈다.
 *
 *   ./gradlew test --tests '*ListingMatchPromptHarnessTest' -Dgoal.harness=true -Dgoal.harness.model=gpt-4o-mini
 *
 * 리포트는 build/goal-parse-harness/listing-match-openai-{model}.txt. 같은 픽스처로 돈
 * listing-match-rule.txt와 나란히 비교한다. 규칙이 못 하는 케이스(에어포스 슬리퍼, 품번 안의
 * 모델, 흰색=화이트)에서 LLM이 이기는지, 대신 거짓 양성이 늘지 않는지를 본다.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "goal.harness", matches = "true")
class ListingMatchPromptHarnessTest {

    private static final String MODEL_PROPERTY = "goal.harness.model";
    private static final Path REPORT_DIRECTORY = Path.of("build", "goal-parse-harness");

    @Test
    void 픽스처_전체를_돌려_LLM_Matcher의_정확도와_비용을_측정한다() throws IOException {
        String model = System.getProperty(MODEL_PROPERTY, "gpt-4o-mini");
        ListingMatchHarnessFixtures.Document fixtures = ListingMatchHarnessFixtures.load();
        TokenCountingChatClient chatClient = new TokenCountingChatClient(createRealClient());
        OpenAiListingMatcher matcher = createMatcher(model, chatClient);

        List<ListingMatchHarnessReport.CaseScore> scores = new ArrayList<>();
        int caseCount = fixtures.cases().size();
        System.out.printf("[하네스] matcher model=%s - %d건 시작%n", model, caseCount);
        long auctionId = 1;
        for (int i = 0; i < caseCount; i++) {
            ListingMatchHarnessCase harnessCase = fixtures.cases().get(i);
            long startedAt = System.currentTimeMillis();
            try {
                MatchResult result = matcher.evaluate(
                        harnessCase.goal().toMatchGoal(), harnessCase.listing().toAuctionListing(auctionId++));
                scores.add(ListingMatchHarnessReport.CaseScore.of(harnessCase, result, System.currentTimeMillis() - startedAt));
                System.out.printf("  [%d/%d] %s - %dms%n", i + 1, caseCount, harnessCase.id(), System.currentTimeMillis() - startedAt);
            } catch (RuntimeException e) {
                scores.add(ListingMatchHarnessReport.CaseScore.failed(harnessCase, System.currentTimeMillis() - startedAt, e));
                System.out.printf("  [%d/%d] %s - 실패: %s%n", i + 1, caseCount, harnessCase.id(), e.getMessage());
            }
        }

        ListingMatchHarnessReport report = ListingMatchHarnessReport.aggregate(
                "matcher=openai, model=%s, fixtures=%s".formatted(model, fixtures.version()), scores, chatClient.usage());
        System.out.println(report.toText());
        Files.createDirectories(REPORT_DIRECTORY);
        Path reportPath = REPORT_DIRECTORY.resolve("listing-match-openai-%s.txt".formatted(model));
        Files.writeString(reportPath, report.toText(), StandardCharsets.UTF_8);
        System.out.println("리포트 저장: " + reportPath.toAbsolutePath());
    }

    private OpenAiVisionClient createRealClient() {
        OpenAiVisionClient client = new OpenAiVisionClient(new ObjectMapper(), new RestTemplate());
        ReflectionTestUtils.setField(client, "apiKey", System.getenv("OPENAI_API_KEY"));
        return client;
    }

    private OpenAiListingMatcher createMatcher(String model, ChatCompletionClient chatClient) {
        ListingMatcherProperties properties = new ListingMatcherProperties();
        properties.setModel(model);
        ModelAliases modelAliases = new ModelAliases();
        return new OpenAiListingMatcher(
                chatClient, new ObjectMapper(), new MatchResultValidator(modelAliases),
                Mockito.mock(AiCallLogger.class), properties, new PromptTemplateLoader(), modelAliases);
    }

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

        ListingMatchHarnessReport.Usage usage() {
            return new ListingMatchHarnessReport.Usage(apiCalls, promptTokens, completionTokens);
        }
    }
}
