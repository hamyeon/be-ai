package com.vintic.backend.ai.vision.dto;

// 사진만으로는 확인할 수 없어 비워둔 필드와 그 사유.
// 사용자에게 "어느 부위를 다시 찍어달라"고 안내하려면 무엇이 왜 비었는지가 필요하다.
public record VisionUnreadable(
        String field,
        String reason
) {
}
