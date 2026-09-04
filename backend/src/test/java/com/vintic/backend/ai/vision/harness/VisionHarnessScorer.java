package com.vintic.backend.ai.vision.harness;

import com.vintic.backend.ai.vision.dto.ConditionGrade;
import com.vintic.backend.ai.vision.dto.VisionAnalysisResult;
import com.vintic.backend.common.exception.AiResponseFormatException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Vision 응답 한 건을 정답 라벨과 대조해 필드별 결과를 매기는 채점기.
//
// "맞았다/틀렸다" 2분법 대신 ABSTAINED(값을 안 채움)를 따로 둔다.
// 지금 프롬프트의 size 문제처럼, 못 채운 것과 틀리게 채운 것은 성격이 완전히 다르기 때문이다.
// 틀리게 채우는 쪽(WRONG)이 환각이라 훨씬 나쁘고, 못 채우는 쪽(ABSTAINED)은 사용자 확인으로 메울 수 있다.
public final class VisionHarnessScorer {

    // 등급을 좋은 순서로 나열한 것. 인접한 등급끼리 틀린 경우는 NEAR로 따로 센다.
    private static final List<ConditionGrade> GRADE_ORDER =
            List.of(ConditionGrade.DS, ConditionGrade.A, ConditionGrade.B, ConditionGrade.C);

    private VisionHarnessScorer() {
    }

    public enum Field {
        BRAND, MODEL_NAME, COLOR, SIZE, BOX_INCLUDED, CONDITION_GRADE
    }

    public enum Outcome {
        CORRECT,      // 값을 채웠고 정답
        NEAR,         // 값을 채웠고 근사 - conditionGrade는 한 등급 차이, color는 색 계열만 일치
        WRONG,        // 값을 채웠는데 오답 = 환각
        ABSTAINED,    // null 또는 UNKNOWN - 모르겠다고 기권
        NOT_LABELED   // 픽스처에 정답이 없어 채점 제외
    }

    // 실패를 한 덩어리로 세면 JSON 준수율을 낼 수 없다. API가 거절해서 응답 자체가 없는 것과,
    // 응답은 왔는데 형식이 어긋난 것은 프롬프트 품질 측면에서 전혀 다른 사건이다.
    public enum FailureKind {
        API_ERROR,      // 응답을 받지 못함 - 준수율 계산의 분모에서 빠진다
        FORMAT_ERROR    // 응답은 왔는데 약속한 JSON으로 읽지 못함 - 준수율을 떨어뜨린다
    }

    // mismatches: 틀리거나 근사로 판정된 필드에 대해 "기대=..., 실제=..."를 담는다.
    //
    // 결과에 O/X만 남기면 왜 틀렸는지 알 수 없다. 사이즈 정답이 290인데 285를 낸 것(변환 실수)과
    // 250을 낸 것(엉뚱한 라벨을 읽음)은 고칠 방법이 완전히 다른데, 표에는 둘 다 X로만 보인다.
    public record CaseScore(
            String caseId,
            Map<Field, Outcome> outcomes,
            Map<Field, String> mismatches,
            long latencyMs,
            String failureMessage,
            FailureKind failureKind
    ) {

        public static CaseScore failed(String caseId, long latencyMs, Throwable error) {
            Map<Field, Outcome> outcomes = new EnumMap<>(Field.class);
            for (Field field : Field.values()) {
                outcomes.put(field, Outcome.ABSTAINED);
            }
            // 메시지 문자열이 아니라 예외 타입으로 가른다. 메시지를 다듬는 순간
            // 조용히 오분류되기 때문이다.
            FailureKind kind = error instanceof AiResponseFormatException
                    ? FailureKind.FORMAT_ERROR
                    : FailureKind.API_ERROR;
            return new CaseScore(caseId, outcomes, Map.of(), latencyMs, error.getMessage(), kind);
        }

        public boolean isFailure() {
            return failureMessage != null;
        }

        // 응답 자체는 받아낸 케이스. JSON 준수율의 분모가 된다.
        public boolean receivedResponse() {
            return failureKind != FailureKind.API_ERROR;
        }
    }

    public static CaseScore score(VisionHarnessCase harnessCase, VisionAnalysisResult result, long latencyMs) {
        VisionHarnessCase.Expected expected = harnessCase.expected();

        Map<Field, Outcome> outcomes = new EnumMap<>(Field.class);
        outcomes.put(Field.BRAND, scoreBrand(expected.brand(), result.brand()));
        outcomes.put(Field.MODEL_NAME, scoreModelName(expected.modelKeywords(), result.modelName()));
        outcomes.put(Field.COLOR, scoreColor(expected.colorKeywords(), result.color()));
        outcomes.put(Field.SIZE, scoreEquals(expected.size(), result.size()));
        outcomes.put(Field.BOX_INCLUDED, scoreEquals(expected.boxIncluded(), result.boxIncluded()));
        outcomes.put(Field.CONDITION_GRADE, scoreConditionGrade(expected.conditionGrade(), result.conditionGrade()));

        Map<Field, String> mismatches = new EnumMap<>(Field.class);
        recordMismatch(mismatches, outcomes, Field.BRAND, expected.brand(), result.brand());
        recordMismatch(mismatches, outcomes, Field.MODEL_NAME, expected.modelKeywords(), result.modelName());
        recordMismatch(mismatches, outcomes, Field.COLOR, expected.colorKeywords(), result.color());
        recordMismatch(mismatches, outcomes, Field.SIZE, expected.size(), result.size());
        recordMismatch(mismatches, outcomes, Field.BOX_INCLUDED, expected.boxIncluded(), result.boxIncluded());
        recordMismatch(mismatches, outcomes, Field.CONDITION_GRADE, expected.conditionGrade(), result.conditionGrade());

        return new CaseScore(harnessCase.id(), outcomes, Map.copyOf(mismatches), latencyMs, null, null);
    }

    private static void recordMismatch(
            Map<Field, String> mismatches, Map<Field, Outcome> outcomes, Field field, Object expected, Object actual) {
        Outcome outcome = outcomes.get(field);
        if (outcome == Outcome.WRONG || outcome == Outcome.NEAR) {
            mismatches.put(field, "기대=%s, 실제=%s".formatted(display(expected), display(actual)));
        }
    }

    private static String display(Object value) {
        if (value == null) {
            return "없음";
        }
        String text = value instanceof List<?> list ? list.toString() : value.toString();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static Outcome scoreBrand(List<String> expectedBrands, String actualBrand) {
        if (expectedBrands == null || expectedBrands.isEmpty()) {
            return Outcome.NOT_LABELED;
        }
        String actual = normalize(actualBrand);
        if (actual.isEmpty()) {
            return Outcome.ABSTAINED;
        }
        // "Nike"로 답할 수도, "Nike Air Jordan"으로 답할 수도 있어 양방향 포함으로 본다.
        boolean matched = expectedBrands.stream()
                .map(VisionHarnessScorer::normalize)
                .filter(expected -> !expected.isEmpty())
                .anyMatch(expected -> actual.contains(expected) || expected.contains(actual));
        return matched ? Outcome.CORRECT : Outcome.WRONG;
    }

    private static Outcome scoreModelName(List<String> expectedKeywords, String actualModelName) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return Outcome.NOT_LABELED;
        }
        String actual = normalize(actualModelName);
        if (actual.isEmpty()) {
            return Outcome.ABSTAINED;
        }
        // 모델명 표기는 제각각이라 완전 일치 대신 식별에 필요한 키워드가 전부 들어있는지로 본다.
        boolean matched = expectedKeywords.stream()
                .map(VisionHarnessScorer::normalize)
                .allMatch(actual::contains);
        return matched ? Outcome.CORRECT : Outcome.WRONG;
    }

    // 색상은 2단계로 채점한다(#90). 판매자 표기와 문자 그대로 겹치면 정답,
    // 표기는 달라도 색 계열이 겹치면 근사(NEAR). 프롬프트가 공식 컬러웨이명을 금지하므로
    // "Cream"과 "Beige"처럼 같은 계열의 다른 표기를 오답으로 갈라서는 안 된다.
    private static Outcome scoreColor(List<String> expectedKeywords, String actualColor) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return Outcome.NOT_LABELED;
        }
        if (actualColor == null || normalize(actualColor).isEmpty()) {
            return Outcome.ABSTAINED;
        }
        // 정확 일치도 단어 단위로 본다. 부분 문자열로 보면 "Titanium"이 "tan"에 걸린다.
        java.util.Set<String> actualWords = colorWords(actualColor);
        boolean exact = expectedKeywords.stream()
                .map(VisionHarnessScorer::normalize)
                .filter(expected -> !expected.isEmpty())
                .anyMatch(actualWords::contains);
        if (exact) {
            return Outcome.CORRECT;
        }
        var expectedFamilies = ColorFamilies.of(String.join(" ", expectedKeywords));
        var actualFamilies = ColorFamilies.of(actualColor);
        boolean familyOverlap = expectedFamilies.stream().anyMatch(actualFamilies::contains);
        return familyOverlap ? Outcome.NEAR : Outcome.WRONG;
    }

    // 응답 색상을 단어 집합으로 바꾼다. "wolf grey" 같은 두 단어 표기와 맞추기 위해
    // 인접 단어를 이어붙인 것도 포함한다 ("Wolf Grey / White" -> wolf, grey, white, wolfgrey, greywhite).
    private static java.util.Set<String> colorWords(String text) {
        String[] words = text.toLowerCase().split("[^a-z0-9]+");
        java.util.Set<String> result = new java.util.HashSet<>();
        for (int i = 0; i < words.length; i++) {
            if (!words[i].isEmpty()) {
                result.add(words[i]);
                if (i + 1 < words.length && !words[i + 1].isEmpty()) {
                    result.add(words[i] + words[i + 1]);
                }
            }
        }
        return result;
    }

    private static Outcome scoreEquals(Object expected, Object actual) {
        if (expected == null) {
            return Outcome.NOT_LABELED;
        }
        if (actual == null) {
            return Outcome.ABSTAINED;
        }
        return expected.equals(actual) ? Outcome.CORRECT : Outcome.WRONG;
    }

    private static Outcome scoreConditionGrade(String expectedGrade, ConditionGrade actualGrade) {
        if (expectedGrade == null || expectedGrade.isBlank()) {
            return Outcome.NOT_LABELED;
        }
        if (actualGrade == null || actualGrade == ConditionGrade.UNKNOWN) {
            return Outcome.ABSTAINED;
        }
        ConditionGrade expected = ConditionGrade.valueOf(expectedGrade.trim().toUpperCase());
        if (expected == actualGrade) {
            return Outcome.CORRECT;
        }
        int expectedIndex = GRADE_ORDER.indexOf(expected);
        int actualIndex = GRADE_ORDER.indexOf(actualGrade);
        if (expectedIndex < 0 || actualIndex < 0) {
            return Outcome.WRONG;
        }
        return Math.abs(expectedIndex - actualIndex) == 1 ? Outcome.NEAR : Outcome.WRONG;
    }

    // 표기 흔들림(대소문자, 공백, 하이픈)을 없애고 비교한다. "Slip-On" -> "slipon"
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
