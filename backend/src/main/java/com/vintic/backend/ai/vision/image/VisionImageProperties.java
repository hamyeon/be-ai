package com.vintic.backend.ai.vision.image;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// application.yml의 vision.image.* 값을 바인딩한다 (#102).
//
// max-edge는 정확도와 비용을 정면으로 맞바꾸는 값이라 코드에 박아두면 조정할 때마다 재배포해야 한다.
// 기본 768의 근거: 하네스에서 detail=low(512px 상당) 회차가 기본 회차보다 나았다
// (브랜드 93% vs 86%, 상태 등급 50% vs 36%). 저해상도가 정확도를 깎지 않았으므로 그보다
// 선명한 768px는 여유가 있다고 보고, 512/1024와 함께 하네스로 다시 잰다.
@Component
@ConfigurationProperties(prefix = "vision.image")
@Getter
@Setter
public class VisionImageProperties {

    // 분석용 사본의 긴 변 최대 길이(px). 0 이하면 리사이즈하지 않는다.
    private int maxEdge = 768;
}
