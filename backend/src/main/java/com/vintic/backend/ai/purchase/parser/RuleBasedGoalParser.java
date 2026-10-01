package com.vintic.backend.ai.purchase.parser;

import com.vintic.backend.ai.purchase.dto.GoalCondition;
import com.vintic.backend.ai.purchase.dto.GoalDraft;
import com.vintic.backend.ai.purchase.model.BrandAliases;
import com.vintic.backend.ai.purchase.model.ModelAliases;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 규칙 기반 Goal 파서. LLM 없이 별칭 표와 정규식만으로 초안을 만든다.
//
// 역할 셋:
//   1. 백엔드가 루프를 만드는 동안 쓰는 Fake 구현(설계안 6-4).
//   2. LLM 파서가 실패했을 때의 fallback - 빈 폼보다는 절반 채워진 폼이 낫다.
//   3. 하네스 기준선. LLM 파서가 이보다 나은지 숫자로 보여야 채택한다.
//
// 결정적이고 API 비용이 없다. 대신 "삼바 흰색 깨끗한 걸로" 같은 문장에서 "깨끗한"이 A급인지,
// "흰색"이 자유 조건인지는 규칙으로 밖에 못 하므로 confidence 상한을 0.85로 둔다 -
// 확인 화면에서 항상 한 번 더 보게 하려는 것이다.
@Component
@RequiredArgsConstructor
public class RuleBasedGoalParser implements GoalParser {

    static final double MAX_CONFIDENCE = 0.85;

    // 예산 상한으로 믿을 수 있는 범위. 밖이면 파싱 오류로 보고 비운다.
    private static final long MIN_PLAUSIBLE_AMOUNT = 10_000L;
    private static final long MAX_PLAUSIBLE_AMOUNT = 100_000_000L;

    // "15만원", "15만", "15만5천", "15만원대", "1.5만"
    private static final Pattern MAN_WON =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*만\\s*(?:(\\d)\\s*천)?\\s*원?\\s*(대)?");
    // "150,000원", "150000" - 4자리 이하 숫자는 모델 번호(1461, 2002)일 수 있어 5자리부터만
    private static final Pattern PLAIN_WON =
            Pattern.compile("(?<![\\d,.])(\\d{1,3}(?:,\\d{3})+|\\d{5,})\\s*원?");
    // 금액 뒤에 붙어 "최소"를 뜻하는 말 / 금액 앞의 "최소"
    private static final Pattern MIN_AFTER = Pattern.compile("^\\s*(이상|부터|넘)");
    private static final Pattern MIN_BEFORE = Pattern.compile("(최소|적어도)\\s*$");

    // 사이즈: 220~325, 5 단위. 앞뒤에 숫자가 이어지면 금액(250000)·품번(2002r)이므로 제외.
    private static final Pattern SIZE = Pattern.compile(
            "(?<!\\d)(2[2-9][05]|3[0-2][05])(?!\\d)\\s*(?:mm|사이즈|size)?", Pattern.CASE_INSENSITIVE);

    // 상태 등급. 명시 등급(a급) > 새상품 표현 > 근사 표현 순으로 본다.
    private static final Pattern GRADE_LETTER = Pattern.compile("(?<![a-z])([abcs])\\s*(?:급|등급)");
    private static final Pattern DS_WORDS = Pattern.compile(
            "새\\s*상품|새\\s*제품|새\\s*거|새\\s*것|미착용|미개봉|데드\\s*스탁|deadstock|(?<![a-z])ds(?![a-z])");
    private static final Pattern S_WORDS = Pattern.compile("거의\\s*새|극상|민트급?");
    private static final Pattern A_WORDS = Pattern.compile("상태\\s*좋|깨끗|깔끔|양호|사용감\\s*(없|적|거의)|하자\\s*없");
    private static final Pattern B_WORDS = Pattern.compile("사용감|중고");
    private static final Pattern C_WORDS = Pattern.compile("막\\s*신|하자|많이\\s*신");
    private static final Pattern CONDITION_ANY = Pattern.compile("상태\\s*(상관\\s*없|무관|아무)");

    // 자유 조건에서 지울 군더더기. "사고 싶어요" 류와 어절 끝의 일부 조사.
    // 이/가/은/는/의/에/도는 지우지 않는다 - "그레이", "네이비" 같은 색 이름의 끝 글자를 잘라낸다.
    private static final Pattern FILLER = Pattern.compile(
            "사고\\s*싶[어다]요?|사려[고면]?\\s*(해요|합니다|요)?|살래요?|살게요?|살려고요?|구해요|구합니다|구함|"
                    + "찾아요|찾습니다|찾고\\s*있어요?|원해요|원합니다|부탁(해요|드려요|드립니다)?|주세요|줘요?|해줘요?|"
                    + "하나만|하나요?|한\\s*켤레|한\\s*개|1개|최소|최대|이하로?|이내로?|안쪽으로?|까지|정도로?|선에서|이상(?!한)|"
                    + "짜리|급으로|등급으로|으로|걸로|것으로|(?<=\\S)(로|를|을|랑|하고)(?=\\s|$)");
    private static final Pattern NOISE = Pattern.compile("[,./~!?()\\[\\]{}\"'`:;·]+|\\s+");

    private final ModelAliases modelAliases;

    @Override
    public GoalDraft parse(String naturalLanguage) {
        if (naturalLanguage == null || naturalLanguage.isBlank()) {
            return GoalDraft.empty(List.of(GoalDraftWarnings.MODEL_UNKNOWN, GoalDraftWarnings.BUDGET_MISSING));
        }
        String text = naturalLanguage.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> consumed = new ArrayList<>();

        Optional<ModelAliases.Match> model = modelAliases.find(text);
        model.ifPresent(m -> consumed.add(m.matchedAlias()));
        Optional<BrandAliases.Match> brand = BrandAliases.find(text);
        brand.ifPresent(b -> consumed.add(b.matchedAlias()));

        Amount amount = extractMaxAmount(lower, consumed);
        Integer sizeKr = extractSize(lower, consumed);
        GoalCondition condition = extractCondition(lower, consumed);

        String freeText = leftover(text, consumed);

        String brandName = model.map(m -> m.model().brand()).orElse(brand.map(BrandAliases.Match::brand).orElse(null));
        String modelKey = model.map(m -> m.model().modelKey()).orElse(null);
        String modelQuery = model.map(m -> m.model().fullName()).orElse(null);

        List<String> warnings = GoalDraftWarnings.forDraft(
                modelKey, brandName, amount.value(), sizeKr, freeText, amount.minIgnored(), amount.outOfRange());
        if (condition == null) {
            warnings.add(GoalDraftWarnings.CONDITION_MISSING);
        }

        return new GoalDraft(modelQuery, brandName, modelKey, condition, amount.value(), sizeKr, freeText,
                confidence(modelKey, brandName, amount.value(), condition, sizeKr, freeText), warnings);
    }

    private record Amount(Long value, boolean minIgnored, boolean outOfRange) {
    }

    // 문장에 나온 금액 중 "이상/최소"가 붙지 않은 것들의 최댓값을 예산 상한으로 본다.
    // "10만~15만"이면 15만, "최소 10만 최대 15만"이면 15만이다.
    private Amount extractMaxAmount(String lower, List<String> consumed) {
        Long best = null;
        boolean minIgnored = false;
        boolean outOfRange = false;

        Matcher man = MAN_WON.matcher(lower);
        while (man.find()) {
            double manValue = Double.parseDouble(man.group(1));
            long value = Math.round(manValue * 10_000);
            if (man.group(2) != null) {
                value += Long.parseLong(man.group(2)) * 1_000;
            }
            if (man.group(3) != null) {
                // "10만원대" = 10만~19만 → 상한은 다음 자릿수 경계. 5만원대는 6만, 15만원대는 25만.
                long band = (long) Math.pow(10, Math.max(0, (int) Math.floor(Math.log10(manValue)))) * 10_000;
                value = value + band;
            }
            // "사이즈 270만" - 사이즈 숫자 뒤에 붙은 "만"은 조사다.
            if (man.group(2) == null && man.group(3) == null && !man.group().contains("원") && isSizeNumber(man.group(1))) {
                continue;
            }
            consumed.add(man.group());
            if (isMinimum(lower, man)) {
                minIgnored = true;
                continue;
            }
            if (value < MIN_PLAUSIBLE_AMOUNT || value > MAX_PLAUSIBLE_AMOUNT) {
                outOfRange = true;
                continue;
            }
            best = best == null ? value : Math.max(best, value);
        }

        Matcher plain = PLAIN_WON.matcher(lower);
        while (plain.find()) {
            long value = Long.parseLong(plain.group(1).replace(",", ""));
            consumed.add(plain.group());
            if (isMinimum(lower, plain)) {
                minIgnored = true;
                continue;
            }
            if (value < MIN_PLAUSIBLE_AMOUNT || value > MAX_PLAUSIBLE_AMOUNT) {
                outOfRange = true;
                continue;
            }
            best = best == null ? value : Math.max(best, value);
        }
        return new Amount(best, minIgnored, outOfRange && best == null);
    }

    private boolean isSizeNumber(String digits) {
        return SIZE.matcher(digits).matches();
    }

    private boolean isMinimum(String lower, Matcher matcher) {
        String after = lower.substring(matcher.end());
        String before = lower.substring(0, matcher.start());
        return MIN_AFTER.matcher(after).find() || MIN_BEFORE.matcher(before).find();
    }

    private Integer extractSize(String lower, List<String> consumed) {
        Matcher matcher = SIZE.matcher(lower);
        while (matcher.find()) {
            // 이미 금액으로 소비된 구간(예: "250000")은 SIZE 자체가 앞뒤 숫자 검사로 거르지만,
            // "25만" 같은 금액 표기의 숫자와 겹치는 일은 없다(2자리).
            consumed.add(matcher.group());
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private GoalCondition extractCondition(String lower, List<String> consumed) {
        if (CONDITION_ANY.matcher(lower).find()) {
            return null;
        }
        Matcher letter = GRADE_LETTER.matcher(lower);
        if (letter.find()) {
            consumed.add(letter.group());
            return GoalCondition.valueOf(letter.group(1).toUpperCase(Locale.ROOT));
        }
        // "거의 새거"는 S다 - DS 표현("새거")보다 먼저 봐야 한다.
        Matcher s = S_WORDS.matcher(lower);
        if (s.find()) {
            consumed.add(s.group());
            return GoalCondition.S;
        }
        Matcher ds = DS_WORDS.matcher(lower);
        if (ds.find()) {
            consumed.add(ds.group());
            return GoalCondition.DS;
        }
        Matcher a = A_WORDS.matcher(lower);
        if (a.find()) {
            consumed.add(a.group());
            return GoalCondition.A;
        }
        Matcher c = C_WORDS.matcher(lower);
        if (c.find()) {
            consumed.add(c.group());
            return GoalCondition.C;
        }
        Matcher b = B_WORDS.matcher(lower);
        if (b.find()) {
            consumed.add(b.group());
            return GoalCondition.B;
        }
        return null;
    }

    // 원문에서 이미 구조화 필드로 뽑아 쓴 구간과 군더더기를 지우고 남은 것이 자유 조건이다.
    // "박스 있으면 좋음", "시카고 컬러" 같은 것이 남는다. 2글자 미만이면 없는 것으로 본다.
    private String leftover(String text, List<String> consumed) {
        String remaining = text;
        for (String span : consumed) {
            remaining = remaining.replaceFirst("(?i)" + looseSpacing(span), " ");
        }
        remaining = FILLER.matcher(remaining).replaceAll(" ");
        remaining = NOISE.matcher(remaining).replaceAll(" ").trim();
        return remaining.length() < 2 ? null : remaining;
    }

    // 별칭 "뉴발 990"이 원문에서 "뉴발990"/"뉴발-990"으로 적혀 있어도 지울 수 있게,
    // 글자 사이에 공백·하이픈·점을 허용하는 패턴으로 바꾼다.
    private String looseSpacing(String span) {
        StringBuilder pattern = new StringBuilder();
        for (char ch : span.toCharArray()) {
            if (Character.isWhitespace(ch) || ch == '-' || ch == '.') {
                continue;
            }
            if (!pattern.isEmpty()) {
                pattern.append("[\\s\\-.]*");
            }
            pattern.append(Pattern.quote(String.valueOf(ch)));
        }
        return pattern.toString();
    }

    private double confidence(String modelKey, String brand, Long amount, GoalCondition condition,
                              Integer sizeKr, String freeText) {
        double score = 0.2;
        if (modelKey != null) {
            score += 0.35;
        } else if (brand != null) {
            score += 0.1;
        }
        if (amount != null) {
            score += 0.2;
        }
        if (condition != null) {
            score += 0.1;
        }
        if (sizeKr != null) {
            score += 0.05;
        }
        // 해석 못 한 텍스트가 길게 남았다는 건 규칙이 놓친 조건이 있다는 뜻이다.
        if (freeText != null && freeText.length() > 12) {
            score -= 0.1;
        }
        return Math.max(0.0, Math.min(MAX_CONFIDENCE, score));
    }
}
