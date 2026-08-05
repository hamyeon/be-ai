package com.vintic.backend.ai.vision.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

// src/test/resources/vision/harness-fixtures.json을 읽어오는 로더.
public final class VisionHarnessFixtures {

    private static final String FIXTURE_PATH = "vision/harness-fixtures.json";

    private VisionHarnessFixtures() {
    }

    public static Document load() {
        ClassPathResource resource = new ClassPathResource(FIXTURE_PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return new ObjectMapper().readValue(inputStream, Document.class);
        } catch (IOException e) {
            throw new UncheckedIOException("하네스 픽스처를 읽을 수 없습니다: " + FIXTURE_PATH, e);
        }
    }

    public record Document(String version, String description, List<VisionHarnessCase> cases) {
    }
}
