package com.vintic.backend.ai.prompt;

import com.vintic.backend.common.exception.PromptTemplateNotFoundException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

// src/main/resources/prompts/{category}/{name}-{version}.md 파일을 읽어오는 로더.
// 프롬프트 내용을 Java 코드에서 분리하고, 파일명(name-version)으로 버전을 구분한다.
//
// 응답 JSON Schema도 같은 규칙({name}-{version}.schema.json)으로 프롬프트 옆에 둔다.
// 프롬프트와 스키마는 항상 같이 바뀌므로 버전을 따로 관리하면 어긋나기 쉽다.
@Component
public class PromptTemplateLoader {

    private static final String BASE_PATH = "prompts";

    public PromptTemplate load(String category, String name, String version) {
        String path = "%s/%s/%s-%s.md".formatted(BASE_PATH, category, name, version);
        return new PromptTemplate(name, version, read(path, "프롬프트"));
    }

    public String loadSchema(String category, String name, String version) {
        String path = "%s/%s/%s-%s.schema.json".formatted(BASE_PATH, category, name, version);
        return read(path, "응답 스키마");
    }

    private String read(String path, String description) {
        ClassPathResource resource = new ClassPathResource(path);

        if (!resource.exists()) {
            throw new PromptTemplateNotFoundException("%s 파일을 찾을 수 없습니다: %s".formatted(description, path));
        }

        try (InputStream inputStream = resource.getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PromptTemplateNotFoundException(
                    "%s 파일을 읽는 중 오류가 발생했습니다: %s".formatted(description, path), e);
        }
    }
}
