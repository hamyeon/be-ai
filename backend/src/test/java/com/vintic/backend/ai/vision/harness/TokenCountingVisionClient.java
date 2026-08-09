package com.vintic.backend.ai.vision.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.vision.client.OpenAiVisionClient;
import com.vintic.backend.ai.vision.client.VisionChatRequest;
import com.vintic.backend.ai.vision.client.VisionChatResponse;
import org.springframework.web.client.RestTemplate;

// 하네스 전용. 실제 호출은 그대로 하면서 호출 횟수와 토큰 사용량만 누적한다.
//
// 정확도만 보고 detail: high나 단계 분리를 채택하면, 비용이 몇 배가 됐는지 모른 채 결정하게 된다.
// VisionAnalysisService 인터페이스에 사용량을 노출시키면 서비스 코드가 지저분해지므로
// 측정이 필요한 하네스 쪽에서만 클라이언트를 감싼다.
class TokenCountingVisionClient extends OpenAiVisionClient {

    private int apiCalls;
    private int promptTokens;
    private int completionTokens;

    TokenCountingVisionClient(ObjectMapper objectMapper, RestTemplate restTemplate) {
        super(objectMapper, restTemplate);
    }

    @Override
    public VisionChatResponse complete(VisionChatRequest request) {
        VisionChatResponse response = super.complete(request);
        apiCalls++;
        promptTokens += response.promptTokens();
        completionTokens += response.completionTokens();
        return response;
    }

    void reset() {
        apiCalls = 0;
        promptTokens = 0;
        completionTokens = 0;
    }

    VisionHarnessReport.Usage usage() {
        return new VisionHarnessReport.Usage(apiCalls, promptTokens, completionTokens);
    }
}
