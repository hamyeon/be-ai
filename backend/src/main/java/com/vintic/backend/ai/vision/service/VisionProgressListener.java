package com.vintic.backend.ai.vision.service;

import com.vintic.backend.ai.vision.dto.VisionProgress;

// Vision 분석의 단계가 끝날 때마다 불린다(#106). 분석 스레드 안에서 동기로 불리므로 오래 걸리면 안 된다.
// 여기서 난 예외는 분석을 실패시키지 않는다 - 진행 표시는 부가 기능이다.
@FunctionalInterface
public interface VisionProgressListener {

    VisionProgressListener NONE = progress -> { };

    void onStageCompleted(VisionProgress progress);
}
