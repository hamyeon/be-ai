package com.vintic.backend.ai.vision.agent;

import com.vintic.backend.ai.vision.client.VisionImageDetail;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 vision.stage.* 값을 바인딩한다.
//
// 단계별 이미지 해상도(detail)는 정확도와 비용을 정면으로 맞바꾸는 값이라, 코드에 박아두면
// 조정할 때마다 재배포해야 한다. 라벨 판독에 high가 정말 값을 하는지도 아직 측정 중이라
// 바꿔가며 재볼 수 있어야 한다.
//
// 기본값은 "라벨 글자를 읽는 2·3단계만 고해상도"라는 현재 가설이다.
// 실루엣은 512px로 줄여도 알아볼 수 있어 1단계는 low로 둔다.
@Component
@ConfigurationProperties(prefix = "vision.stage")
@Getter
@Setter
public class VisionStageProperties {

    private Stage silhouette = new Stage(VisionImageDetail.LOW, 900);
    private Stage label = new Stage(VisionImageDetail.HIGH, 900);
    private Stage condition = new Stage(VisionImageDetail.HIGH, 1400);

    @Getter
    @Setter
    public static class Stage {

        private VisionImageDetail detail;
        // 응답이 여기 걸려 잘리면 JSON 파싱이 실패한다. 단계마다 응답 길이가 달라 따로 둔다.
        private int maxOutputTokens;

        public Stage() {
        }

        public Stage(VisionImageDetail detail, int maxOutputTokens) {
            this.detail = detail;
            this.maxOutputTokens = maxOutputTokens;
        }
    }
}
