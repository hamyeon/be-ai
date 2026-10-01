package com.vintic.backend.product.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

// 색상 표기(한/영)를 색 계열(family)로 정규화한다 (#93).
//
// 같은 색을 "그레이"/"회색"/"grey"/"gray"/"차콜"로 제각각 적는다. 문자로 비교하면
// 전부 다른 색이 되어 (모델, 색상) 시세 매칭이 안 된다. 표기를 계열로 접으면
// 비교가 계열 대 계열이 되어 표기 흔들림이 사라진다.
//
// crawler/calibration/color_families.py와 같은 체계다 - 시세 CSV를 만드는 쪽과
// 조회하는 쪽이 다른 색 기준을 쓰면 만들어둔 시세가 영영 매칭되지 않는다.
// 표를 고칠 때는 반드시 양쪽을 같이 고치고 CSV를 재생성한다.
//
// 영문은 단어 단위로만 매칭한다("titanium"이 "tan"에 걸리는 오탐 방지).
// 한글은 긴 표기부터 검사한다("오프화이트"가 "화이트"보다 먼저).
public final class ColorFamilies {

    private static final Map<String, Set<String>> TOKEN_TO_FAMILIES = Map.ofEntries(
            Map.entry("black", Set.of("black")), Map.entry("블랙", Set.of("black")),
            Map.entry("검정", Set.of("black")), Map.entry("검은", Set.of("black")),
            Map.entry("흑색", Set.of("black")), Map.entry("먹색", Set.of("black")),
            Map.entry("white", Set.of("white")), Map.entry("화이트", Set.of("white")),
            Map.entry("흰색", Set.of("white")), Map.entry("하양", Set.of("white")),
            Map.entry("하얀", Set.of("white")), Map.entry("백색", Set.of("white")),
            Map.entry("sail", Set.of("white")), Map.entry("세일", Set.of("white")),
            Map.entry("offwhite", Set.of("white", "cream")), Map.entry("오프화이트", Set.of("white", "cream")),
            Map.entry("ivory", Set.of("white", "cream")), Map.entry("아이보리", Set.of("white", "cream")),
            Map.entry("cream", Set.of("cream")), Map.entry("크림", Set.of("cream")),
            Map.entry("beige", Set.of("cream")), Map.entry("베이지", Set.of("cream")),
            Map.entry("sand", Set.of("cream")), Map.entry("샌드", Set.of("cream")),
            Map.entry("oatmeal", Set.of("cream")), Map.entry("오트밀", Set.of("cream")),
            Map.entry("tan", Set.of("cream", "brown")), Map.entry("탄색", Set.of("cream", "brown")),
            Map.entry("brown", Set.of("brown")), Map.entry("브라운", Set.of("brown")),
            Map.entry("갈색", Set.of("brown")),
            Map.entry("chocolate", Set.of("brown")), Map.entry("초콜릿", Set.of("brown")),
            Map.entry("초코", Set.of("brown")),
            Map.entry("mocha", Set.of("brown")), Map.entry("모카", Set.of("brown")),
            Map.entry("gum", Set.of("brown")),
            Map.entry("grey", Set.of("grey")), Map.entry("gray", Set.of("grey")),
            Map.entry("그레이", Set.of("grey")), Map.entry("회색", Set.of("grey")),
            Map.entry("charcoal", Set.of("grey")), Map.entry("차콜", Set.of("grey")),
            Map.entry("챠콜", Set.of("grey")),
            Map.entry("steel", Set.of("grey")), Map.entry("스틸", Set.of("grey")),
            Map.entry("smoke", Set.of("grey")), Map.entry("스모크", Set.of("grey")),
            Map.entry("silver", Set.of("silver", "grey")), Map.entry("실버", Set.of("silver", "grey")),
            Map.entry("은색", Set.of("silver", "grey")),
            Map.entry("metallic", Set.of("silver")), Map.entry("메탈릭", Set.of("silver")),
            Map.entry("gold", Set.of("gold", "yellow")), Map.entry("골드", Set.of("gold", "yellow")),
            Map.entry("금색", Set.of("gold", "yellow")),
            Map.entry("yellow", Set.of("yellow")), Map.entry("옐로우", Set.of("yellow")),
            Map.entry("옐로", Set.of("yellow")), Map.entry("노랑", Set.of("yellow")),
            Map.entry("노란", Set.of("yellow")),
            Map.entry("orange", Set.of("orange")), Map.entry("오렌지", Set.of("orange")),
            Map.entry("주황", Set.of("orange")),
            Map.entry("red", Set.of("red")), Map.entry("레드", Set.of("red")),
            Map.entry("빨강", Set.of("red")), Map.entry("빨간", Set.of("red")),
            Map.entry("crimson", Set.of("red")), Map.entry("크림슨", Set.of("red")),
            Map.entry("burgundy", Set.of("red", "brown")), Map.entry("버건디", Set.of("red", "brown")),
            Map.entry("와인", Set.of("red", "brown")),
            Map.entry("blue", Set.of("blue")), Map.entry("블루", Set.of("blue")),
            Map.entry("파랑", Set.of("blue")), Map.entry("파란", Set.of("blue")),
            Map.entry("navy", Set.of("blue")), Map.entry("네이비", Set.of("blue")),
            Map.entry("남색", Set.of("blue")), Map.entry("곤색", Set.of("blue")),
            Map.entry("royal", Set.of("blue")), Map.entry("로얄", Set.of("blue")),
            Map.entry("sky", Set.of("blue")), Map.entry("하늘색", Set.of("blue")),
            Map.entry("denim", Set.of("blue")), Map.entry("데님", Set.of("blue")),
            Map.entry("green", Set.of("green")), Map.entry("그린", Set.of("green")),
            Map.entry("초록", Set.of("green")), Map.entry("녹색", Set.of("green")),
            Map.entry("mint", Set.of("green")), Map.entry("민트", Set.of("green")),
            Map.entry("forest", Set.of("green")), Map.entry("포레스트", Set.of("green")),
            Map.entry("olive", Set.of("green", "brown")), Map.entry("올리브", Set.of("green", "brown")),
            Map.entry("khaki", Set.of("green", "brown")), Map.entry("카키", Set.of("green", "brown")),
            Map.entry("pink", Set.of("pink")), Map.entry("핑크", Set.of("pink")),
            Map.entry("분홍", Set.of("pink")), Map.entry("rose", Set.of("pink")),
            Map.entry("로즈", Set.of("pink")),
            Map.entry("purple", Set.of("purple")), Map.entry("퍼플", Set.of("purple")),
            Map.entry("보라", Set.of("purple")), Map.entry("violet", Set.of("purple"))
    );

    // 한글은 긴 표기부터 검사해야 "오프화이트"의 cream이 "화이트"에 뭉개지지 않는다
    private static final List<String> KOREAN_TOKENS_LONG_FIRST = TOKEN_TO_FAMILIES.keySet().stream()
            .filter(token -> !token.chars().allMatch(c -> c < 128))
            .sorted((a, b) -> b.length() - a.length())
            .toList();

    private ColorFamilies() {
    }

    /** 텍스트에 등장하는 색 계열의 집합. 없으면 빈 집합. */
    public static Set<String> families(String text) {
        Set<String> found = new TreeSet<>();
        if (text == null || text.isBlank()) {
            return found;
        }
        String lowered = text.toLowerCase(Locale.ROOT);

        String consumed = lowered;
        for (String token : KOREAN_TOKENS_LONG_FIRST) {
            if (consumed.contains(token)) {
                found.addAll(TOKEN_TO_FAMILIES.get(token));
                consumed = consumed.replace(token, " ");
            }
        }
        for (String word : lowered.split("[^a-z]+")) {
            Set<String> families = TOKEN_TO_FAMILIES.get(word);
            if (families != null) {
                found.addAll(families);
            }
        }
        return found;
    }

    /**
     * 시세 버킷 키. 계열 1~2개를 사전순으로 이어붙인다 ("black+white").
     * 3계열 이상은 판독이 혼란스러운 표기이므로 색상 시세를 시도하지 않는다(empty).
     * CSV를 만드는 파이프라인(color_families.py)과 같은 규칙이다.
     */
    public static Optional<String> colorKey(String text) {
        Set<String> families = families(text);
        if (families.isEmpty() || families.size() > 2) {
            return Optional.empty();
        }
        return Optional.of(String.join("+", new ArrayList<>(families)));
    }
}
