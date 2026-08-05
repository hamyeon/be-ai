package com.vintic.backend.ai.vision.client;

// Vision 호출 한 번의 결과. 본문뿐 아니라 토큰 사용량과 소요 시간도 같이 들고 나온다.
//
// detail: high를 켤지 말지는 "정확도가 얼마나 오르는가"만으로는 결정할 수 없고
// 비용/지연이 얼마나 늘어나는지와 같이 봐야 해서, 호출 단위로 사용량을 남긴다.
public record VisionChatResponse(
        String content,
        int promptTokens,
        int completionTokens,
        long latencyMs
) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
