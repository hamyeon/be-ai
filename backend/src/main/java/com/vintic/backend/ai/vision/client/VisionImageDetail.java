package com.vintic.backend.ai.vision.client;

// OpenAI Vision의 이미지 해상도 옵션. 요청 본문의 image_url.detail에 그대로 실린다.
//
// low는 이미지를 512x512로 줄여 고정 비용(gpt-4o 기준 85토큰)만 쓰고,
// high는 512x512 타일로 쪼개 타일당 추가 비용을 쓴다. 정확도와 비용이 정면으로 맞바꿈이라
// 단계마다 필요한 만큼만 올려야 한다. (실루엣 판별에는 low로 충분하고, 라벨 글자 판독에는 high가 필요하다)
public enum VisionImageDetail {

    LOW("low"),
    HIGH("high"),
    AUTO("auto");

    private final String value;

    VisionImageDetail(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
