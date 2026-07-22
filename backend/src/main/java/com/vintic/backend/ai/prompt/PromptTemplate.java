package com.vintic.backend.ai.prompt;

// 어떤 프롬프트(name/version)로 분석했는지 코드/로그에서 확인할 수 있도록 내용과 함께 들고 다니는 값 객체
public record PromptTemplate(
        String name,
        String version,
        String content
) {
}
