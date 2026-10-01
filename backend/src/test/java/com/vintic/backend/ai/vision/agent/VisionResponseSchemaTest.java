package com.vintic.backend.ai.vision.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// 단계별 응답 스키마가 OpenAI Structured Outputs(strict) 규칙을 지키는지 확인한다.
//
// strict 스키마는 규칙을 어기면 API가 400을 내는데, 그건 실제 호출을 해봐야 알 수 있다.
// 스키마 파일을 고칠 때마다 유료 호출로 확인할 수는 없으므로 규칙 자체를 여기서 검증한다.
class VisionResponseSchemaTest {

    // strict 모드에서 지원되지 않는 검증 키워드. 하나라도 들어가면 스키마 전체가 거부된다.
    private static final Set<String> UNSUPPORTED_KEYWORDS = Set.of(
            "minLength", "maxLength", "pattern", "format",
            "minimum", "maximum", "multipleOf",
            "minItems", "maxItems", "uniqueItems", "contains", "minContains", "maxContains",
            "minProperties", "maxProperties", "patternProperties", "propertyNames",
            "unevaluatedItems", "unevaluatedProperties", "default"
    );

    private final PromptTemplateLoader loader = new PromptTemplateLoader();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // "단계" 또는 "단계:버전". 버전을 생략하면 v2다. #106에서 출력을 줄인 v3가 추가돼 둘 다 검사한다.
    private JsonNode schemaOf(String nameAndVersion) throws Exception {
        String[] parts = nameAndVersion.split(":");
        String version = parts.length > 1 ? parts[1] : "v2";
        return objectMapper.readTree(loader.loadSchema("vision", parts[0], version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"silhouette:v2", "label:v2", "condition:v2", "silhouette:v3", "label:v3", "condition:v3"})
    void 스키마_최상위는_객체다(String name) throws Exception {
        assertThat(schemaOf(name).path("type").asText()).isEqualTo("object");
    }

    @ParameterizedTest
    @ValueSource(strings = {"silhouette:v2", "label:v2", "condition:v2", "silhouette:v3", "label:v3", "condition:v3"})
    void 모든_객체가_additionalProperties_false를_갖는다(String name) throws Exception {
        List<String> violations = new ArrayList<>();
        forEachObjectSchema(schemaOf(name), "$", (path, node) -> {
            if (!node.path("additionalProperties").isBoolean() || node.path("additionalProperties").asBoolean()) {
                violations.add(path);
            }
        });

        assertThat(violations).as("additionalProperties: false가 빠진 위치").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"silhouette:v2", "label:v2", "condition:v2", "silhouette:v3", "label:v3", "condition:v3"})
    void 모든_객체가_속성_전부를_required에_적는다(String name) throws Exception {
        // strict 모드에서는 선택 필드를 required에서 빼는 게 아니라 타입에 null을 더해 표현해야 한다.
        List<String> violations = new ArrayList<>();
        forEachObjectSchema(schemaOf(name), "$", (path, node) -> {
            Set<String> properties = fieldNames(node.path("properties"));
            Set<String> required = new HashSet<>();
            node.path("required").forEach(item -> required.add(item.asText()));
            if (!required.equals(properties)) {
                violations.add("%s (properties=%s, required=%s)".formatted(path, properties, required));
            }
        });

        assertThat(violations).as("properties와 required가 어긋난 위치").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"silhouette:v2", "label:v2", "condition:v2", "silhouette:v3", "label:v3", "condition:v3"})
    void 지원되지_않는_검증_키워드를_쓰지_않는다(String name) throws Exception {
        List<String> violations = new ArrayList<>();
        collectUnsupportedKeywords(schemaOf(name), "$", violations);

        assertThat(violations).as("strict 모드가 거부하는 키워드").isEmpty();
    }

    @Test
    void 근거_검증이_다루는_필드는_모두_어딘가의_evidence_열거값에_들어있다() throws Exception {
        // VisionEvidenceValidator가 근거를 요구하는 필드인데 어느 스키마에서도 evidence로 낼 수 없다면,
        // 그 필드는 항상 근거 없음으로 판정돼 조용히 비워진다.
        // 버전마다 따로 본다. 합쳐서 보면 v3에서 빠진 필드를 v2가 가려준다.
        for (String version : List.of("v2", "v3")) {
            Set<String> evidenceFields = new HashSet<>();
            for (String stage : List.of("silhouette", "label", "condition")) {
                schemaOf(stage + ":" + version).path("properties").path("evidence").path("items")
                        .path("properties").path("field").path("enum")
                        .forEach(value -> evidenceFields.add(value.asText()));
            }

            assertThat(evidenceFields).as(version)
                    .contains("brand", "modelName", "color", "size", "boxIncluded", "conditionGrade");
        }
    }

    @Test
    void 사이즈_근거는_읽어낸_글자를_담을_수_있어야_한다() throws Exception {
        // 검증기가 size에 대해 observedText를 요구하므로, 스키마가 문자열을 허용해야 한다.
        JsonNode observedText = schemaOf("label").path("properties").path("evidence").path("items")
                .path("properties").path("observedText").path("type");

        assertThat(observedText.toString()).contains("string");
    }

    private Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private interface ObjectSchemaVisitor {
        void visit(String path, JsonNode node);
    }

    // "type": "object"인 노드를 재귀적으로 모두 방문한다.
    private void forEachObjectSchema(JsonNode node, String path, ObjectSchemaVisitor visitor) {
        if (!node.isObject()) {
            return;
        }
        if ("object".equals(node.path("type").asText())) {
            visitor.visit(path, node);
            JsonNode properties = node.path("properties");
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                forEachObjectSchema(properties.get(name), path + "." + name, visitor);
            }
        }
        if (node.has("items")) {
            forEachObjectSchema(node.get("items"), path + "[]", visitor);
        }
    }

    private void collectUnsupportedKeywords(JsonNode node, String path, List<String> violations) {
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (UNSUPPORTED_KEYWORDS.contains(name)) {
                    violations.add(path + "." + name);
                }
                collectUnsupportedKeywords(node.get(name), path + "." + name, violations);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectUnsupportedKeywords(node.get(i), path + "[" + i + "]", violations);
            }
        }
    }
}
