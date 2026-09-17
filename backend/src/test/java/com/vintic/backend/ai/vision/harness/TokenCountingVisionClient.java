package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.vision.client.ChatCompletionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 하네스 전용. 실제 호출은 그대로 하면서 호출 한 번마다 단계·지연·토큰을 기록한다.
//
// 정확도만 보고 detail: high나 단계 분리를 채택하면, 비용이 몇 배가 됐는지 모른 채 결정하게 된다.
// VisionAnalysisService 인터페이스에 사용량을 노출시키면 서비스 코드가 지저분해지므로
// 측정이 필요한 하네스 쪽에서만 클라이언트를 감싼다.
//
// 합계만 남기면 "3단계 중 어디가 느린가"를 알 수 없다(#106). 그래서 호출 단위로 쌓고 집계는 리포트가 한다.
//
// 상속이 아니라 위임이다. OpenAiVisionClient를 상속하면 Claude 클라이언트는 셀 수 없다.
class TokenCountingVisionClient implements ChatCompletionClient {

    // StagedVisionAnalysisService가 붙이는 스키마 이름: vision_{단계}_{버전}
    private static final Pattern STAGE_SCHEMA_NAME = Pattern.compile("^vision_(.+)_v\\d+$");
    static final String SINGLE_CALL_STAGE = "single";

    private final ChatCompletionClient delegate;
    private final List<VisionHarnessReport.Call> calls = new ArrayList<>();

    TokenCountingVisionClient(ChatCompletionClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public VisionChatResponse complete(VisionChatRequest request) {
        // 벤더마다 latencyMs를 재는 범위가 달라질 수 있어 여기서 같은 기준으로 잰다.
        // 429 재시도 대기가 포함된다 - 사용자가 실제로 기다리는 시간이 그렇기 때문이다.
        long startedAt = System.nanoTime();
        VisionChatResponse response = delegate.complete(request);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        calls.add(new VisionHarnessReport.Call(
                stageOf(request),
                latencyMs,
                response.promptTokens(),
                response.completionTokens(),
                request.imageUrls() == null ? 0 : request.imageUrls().size(),
                request.detail() == null ? null : request.detail().value()
        ));
        return response;
    }

    void reset() {
        calls.clear();
    }

    int callCount() {
        return calls.size();
    }

    // 케이스 하나가 부른 호출만 잘라낸다. 케이스 시작 전에 callCount()로 위치를 잡아둔다.
    List<VisionHarnessReport.Call> callsSince(int fromIndex) {
        return List.copyOf(calls.subList(fromIndex, calls.size()));
    }

    VisionHarnessReport.Usage usage() {
        return VisionHarnessReport.Usage.from(calls);
    }

    // 요청에 단계 이름이 따로 없어서 응답 스키마 이름에서 읽는다.
    // 스키마가 없는 V1(한 번에 다 묻는 방식)은 단계가 하나뿐이다.
    static String stageOf(VisionChatRequest request) {
        if (request.responseSchema() == null || request.responseSchema().name() == null) {
            return SINGLE_CALL_STAGE;
        }
        String schemaName = request.responseSchema().name();
        Matcher matcher = STAGE_SCHEMA_NAME.matcher(schemaName);
        return matcher.matches() ? matcher.group(1) : schemaName;
    }
}
