package com.vintic.backend.ai.prompt;

import com.vintic.backend.common.exception.PromptTemplateNotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateLoaderTest {

    private final PromptTemplateLoader sut = new PromptTemplateLoader();

    @Test
    void 존재하는_프롬프트_파일을_읽어온다() {
        PromptTemplate template = sut.load("vision", "product-analysis-system", "v1");

        assertThat(template.name()).isEqualTo("product-analysis-system");
        assertThat(template.version()).isEqualTo("v1");
        assertThat(template.content()).contains("used sneaker product analysis expert");
        assertThat(template.content()).contains("conditionGrade");
    }

    @Test
    void 존재하지_않는_프롬프트_파일이면_예외를_던진다() {
        assertThatThrownBy(() -> sut.load("vision", "product-analysis-system", "v99"))
                .isInstanceOf(PromptTemplateNotFoundException.class)
                .hasMessageContaining("prompts/vision/product-analysis-system-v99.md");
    }

    @Test
    void 프롬프트와_같은_이름의_응답_스키마를_읽어온다() {
        String schema = sut.loadSchema("vision", "label", "v2");

        assertThat(schema).contains("\"sizeLabelText\"");
        assertThat(schema).contains("\"additionalProperties\": false");
    }

    @Test
    void 존재하지_않는_스키마_파일이면_예외를_던진다() {
        assertThatThrownBy(() -> sut.loadSchema("vision", "label", "v99"))
                .isInstanceOf(PromptTemplateNotFoundException.class)
                .hasMessageContaining("prompts/vision/label-v99.schema.json");
    }
}
