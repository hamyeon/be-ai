package com.vintic.backend.ai.vision.harness;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 색상 표기를 색 계열로 묶는다. color 채점의 "근사 일치" 판정에 쓴다.
//
// 왜 계열 채점인가: 프롬프트가 "공식 컬러웨이명은 라벨에 없으면 쓰지 말라"고 막아둬서,
// 같은 신발을 보고 "Cream"이라 할 수도 "Beige"라 할 수도 있다. 둘을 오답으로 가르면
// 프롬프트를 잘 지킨 응답이 벌점을 받는다. 반대로 White를 Black이라 하면 진짜 오답이다.
//
// 단어 단위로만 본다(부분 문자열 금지). "titanium"이 "tan"에 걸리는 식의
// 오탐을 막기 위해서다.
//
// 경계 판단 근거(트레이드오프에 기록한 대로 사람이 정한 선이다):
//   - ivory/tan처럼 두 계열에 걸치는 색은 두 계열 모두에 넣는다
//   - silver는 금속색이지만 회색 계열로도 인정한다 (실버문 vs 회색 논쟁 방지)
//   - 패턴(체커보드 등)은 색이 아니므로 여기 넣지 않는다. 첫 실측에서 체커보드를
//     흑백으로 가정했다가 빨강·파랑 체커 신발에 오답을 냈다 - 패턴 라벨은
//     픽스처의 colorKeywords에서 패턴 단어로 직접 다룬다
final class ColorFamilies {

    private static final Map<String, Set<String>> TOKEN_TO_FAMILIES = Map.ofEntries(
            Map.entry("black", Set.of("black")),
            Map.entry("white", Set.of("white")),
            Map.entry("offwhite", Set.of("white", "cream")),
            Map.entry("sail", Set.of("white")),
            Map.entry("ivory", Set.of("white", "cream")),
            Map.entry("cream", Set.of("cream")),
            Map.entry("beige", Set.of("cream")),
            Map.entry("sand", Set.of("cream")),
            Map.entry("oatmeal", Set.of("cream")),
            Map.entry("tan", Set.of("cream", "brown")),
            Map.entry("brown", Set.of("brown")),
            Map.entry("chocolate", Set.of("brown")),
            Map.entry("mocha", Set.of("brown")),
            Map.entry("gum", Set.of("brown")),
            Map.entry("grey", Set.of("grey")),
            Map.entry("gray", Set.of("grey")),
            Map.entry("charcoal", Set.of("grey")),
            Map.entry("steel", Set.of("grey")),
            Map.entry("smoke", Set.of("grey")),
            Map.entry("silver", Set.of("silver", "grey")),
            Map.entry("metallic", Set.of("silver")),
            Map.entry("gold", Set.of("gold", "yellow")),
            Map.entry("yellow", Set.of("yellow")),
            Map.entry("orange", Set.of("orange")),
            Map.entry("red", Set.of("red")),
            Map.entry("crimson", Set.of("red")),
            Map.entry("burgundy", Set.of("red", "brown")),
            Map.entry("blue", Set.of("blue")),
            Map.entry("navy", Set.of("blue")),
            Map.entry("royal", Set.of("blue")),
            Map.entry("sky", Set.of("blue")),
            Map.entry("green", Set.of("green")),
            Map.entry("mint", Set.of("green")),
            Map.entry("olive", Set.of("green", "brown")),
            Map.entry("khaki", Set.of("green", "brown")),
            Map.entry("forest", Set.of("green")),
            Map.entry("pink", Set.of("pink")),
            Map.entry("rose", Set.of("pink")),
            Map.entry("purple", Set.of("purple")),
            Map.entry("violet", Set.of("purple"))
    );

    private ColorFamilies() {
    }

    /**
     * 텍스트에 등장하는 색 계열의 집합. "Cream/Collegiate Green" -> {cream, green}.
     * 투톤 신발은 계열이 여러 개 나오는 게 정상이고, 기대 계열과 하나라도 겹치면 근사로 본다.
     */
    static Set<String> of(String text) {
        Set<String> families = new HashSet<>();
        if (text == null) {
            return families;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            Set<String> matched = TOKEN_TO_FAMILIES.get(word);
            if (matched != null) {
                families.addAll(matched);
            }
        }
        return families;
    }
}
