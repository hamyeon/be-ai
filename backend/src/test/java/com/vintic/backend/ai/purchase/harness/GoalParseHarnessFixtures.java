package com.vintic.backend.ai.purchase.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

// src/test/resources/purchase/goal-parse-fixtures.json 로더.
final class GoalParseHarnessFixtures {

    static final String PATH = "purchase/goal-parse-fixtures.json";

    record Document(String version, String description, List<GoalParseHarnessCase> cases) {
    }

    private GoalParseHarnessFixtures() {
    }

    static Document load() {
        ClassPathResource resource = new ClassPathResource(PATH);
        try (InputStream inputStream = resource.getInputStream()) {
            return new ObjectMapper().readValue(inputStream, Document.class);
        } catch (IOException e) {
            throw new IllegalStateException("하네스 픽스처를 읽지 못했습니다: " + PATH, e);
        }
    }
}
