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
// detail은 OpenAI 경로에만 적용된다 - Claude에는 대응 파라미터가 없어 무시된다(ClaudeChatClient 참고).
@Component
@ConfigurationProperties(prefix = "vision.stage")
@Getter
@Setter
public class VisionStageProperties {

    private Stage silhouette = new Stage(VisionImageDetail.LOW, 900);
    private Stage label = new Stage(VisionImageDetail.HIGH, 900);
    private Stage condition = new Stage(VisionImageDetail.HIGH, 1400);

    // 단계를 어떻게 부를지(#106). 분석 시간은 순차 실행이면 세 단계의 합, 동시 실행이면 가장 느린 단계에 가까워진다.
    //
    //   SEQUENTIAL               1 -> 2 -> 3. 뒤 단계가 앞 단계 결과를 전부 맥락으로 받는다(기존 방식)
    //   PARALLEL_LABEL_CONDITION 1 -> (2, 3 동시). 3단계가 2단계 라벨 결과를 못 받는다. 2단계 시간만큼 단축
    //   ALL_PARALLEL             1, 2, 3 동시. 어느 단계도 앞 단계 결과를 못 받는다. 가장 느린 한 단계 시간이 된다
    //
    // 동시에 돌려도 되는 근거: 3단계 프롬프트는 앞 단계 결과를 참고 텍스트로만 받고 "앞 단계가 추론했지만 사진에
    // 없는 것은 쓰지 말라"고 못박는다. 2단계는 라벨 글자를 읽는 일이라 1단계 추정 없이도 할 수 있고, 합칠 때
    // 이미 "라벨 값 우선, 없으면 1단계 값" 규칙이 있다. 운영 기본값은 application.yml의 all-parallel이다
    // (Sonnet 5 + v3 조합에서 하네스로 확인). 이 필드 기본값(SEQUENTIAL)은 yml이 없을 때의 fallback이다.
    private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;

    public enum ExecutionMode {
        SEQUENTIAL,
        PARALLEL_LABEL_CONDITION,
        ALL_PARALLEL
    }

    // 동시 실행에 쓰는 스레드 수. 분석 한 건이 PARALLEL_LABEL_CONDITION이면 2개, ALL_PARALLEL이면 3개를 쓴다.
    // 분석 동시 처리 수(analysis.stream.concurrency) x 3 이상이면 스레드를 기다리는 일이 없다.
    private int parallelPoolSize = 6;

    @Getter
    @Setter
    public static class Stage {

        private VisionImageDetail detail;
        // 응답이 여기 걸려 잘리면 JSON 파싱이 실패한다. 단계마다 응답 길이가 달라 따로 둔다.
        private int maxOutputTokens;

        // 아래 셋은 단계별 비용·시간 실험용이다. 비워 두면(null/0) 지금 동작 그대로다.
        //
        // model: 이 단계만 다른 모델로 부른다(예: 실루엣만 Haiku). 비우면 vision.model.
        //   같은 provider의 모델이어야 한다 - 클라이언트는 provider당 하나다.
        private String model;
        // maxEdge: 이 단계에 보낼 사진의 긴 변(px). 0이면 분석용 사본(vision.image.max-edge) 그대로.
        //   Claude는 detail 옵션이 없어서, 해상도를 단계별로 다르게 하려면 줄인 사본을 직접 만들어 보내야 한다.
        private int maxEdge;
        // maxImages: 이 단계에 앞에서부터 몇 장만 보낼지. 0이면 전부. 업로드 순서상 앞쪽이 전체 사진이라는
        //   가정에 기댄 근사치다 - 사진 종류(정면/라벨/밑창)를 받게 되면 그걸로 고르는 게 맞다.
        private int maxImages;

        public Stage() {
        }

        public Stage(VisionImageDetail detail, int maxOutputTokens) {
            this.detail = detail;
            this.maxOutputTokens = maxOutputTokens;
        }
    }
}
