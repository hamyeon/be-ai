package com.vintic.backend.ai.prompt;

import com.vintic.backend.common.exception.PromptTemplateNotFoundException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// src/main/resources/prompts/{category}/{name}-{version}.md 파일을 읽어오는 로더.
// 프롬프트 내용을 Java 코드에서 분리하고, 파일명(name-version)으로 버전을 구분한다.
@Component
public class PromptTemplateLoader {

    private static final String BASE_PATH = "prompts";

    public PromptTemplate load(String category, String name, String version) {
        String path = "%s/%s/%s-%s.md".formatted(BASE_PATH, category, name, version);
        ClassPathResource resource = new ClassPathResource(path);

        if (!resource.exists()) {
            throw new PromptTemplateNotFoundException("프롬프트 파일을 찾을 수 없습니다: " + path);
        }

        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return new PromptTemplate(name, version, content);
        } catch (IOException e) {
            throw new PromptTemplateNotFoundException("프롬프트 파일을 읽는 중 오류가 발생했습니다: " + path, e);
        }
    }
}
