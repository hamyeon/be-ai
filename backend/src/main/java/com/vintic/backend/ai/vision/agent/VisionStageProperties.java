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

    // 2·3단계를 동시에 부를지(#106). 켜면 3단계가 2단계 결과를 못 받는 대신 분석 한 건이 2단계 시간만큼 빨라진다.
    //
    // 3단계 프롬프트는 앞 단계 결과를 참고 텍스트로만 받고 "앞 단계가 추론했지만 사진에 없는 것은 쓰지 말라"고
    // 못박고 있어서 의존이 약하다. 그래도 등급 정확도가 떨어지는지는 하네스로 재야 하므로 기본값은 끈 상태다.
    private boolean parallel = false;

    // 병렬 실행에 쓰는 스레드 수. 분석 한 건이 스레드 하나를 더 쓰므로 분석 동시 처리 수(analysis.stream.concurrency)에 맞춘다.
    private int parallelPoolSize = 4;

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
