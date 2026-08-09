package com.vintic.backend.ai.vision.harness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

// src/test/resources/vision/harness-fixtures-{set}.json을 읽어오는 로더.
//
// 평가 셋이 두 개인 이유는 각각 잴 수 있는 게 다르기 때문이다.
//   daangn        매물당 이미지 1장. 해상도 A/B는 되지만 사이즈 라벨이 찍힌 사진이 없다.
//   fruitsfamily  매물당 여러 장 + 사이즈가 구조화 필드. 사이즈 판독을 잴 수 있는 유일한 셋.
public final class VisionHarnessFixtures {

    public static final String DAANGN = "daangn";
    public static final String FRUITSFAMILY = "fruitsfamily";

    private VisionHarnessFixtures() {
    }

    public static Document load(String setName) {
        String path = "vision/harness-fixtures-%s.json".formatted(setName);
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream inputStream = resource.getInputStream()) {
            return new ObjectMapper().readValue(inputStream, Document.class);
        } catch (IOException e) {
            throw new UncheckedIOException("하네스 픽스처를 읽을 수 없습니다: " + path, e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Document(
            String version,
            String description,
            // 이미지 URL에 해상도를 바꿔 붙일 수 있는 셋인지. 후르츠패밀리는 URL 경로에
            // 리사이즈 규격이 박혀 있고 다른 규격은 403이라 해상도 실험을 할 수 없다.
            Boolean supportsImageVariants,
            List<VisionHarnessCase> cases
    ) {

        public boolean allowsImageVariants() {
            return supportsImageVariants == null || supportsImageVariants;
        }
    }
}
