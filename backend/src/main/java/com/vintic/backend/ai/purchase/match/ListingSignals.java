package com.vintic.backend.ai.purchase.match;

import com.vintic.backend.ai.purchase.model.BrandAliases;
import com.vintic.backend.ai.purchase.model.ModelAliases;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 매물 텍스트에서 결정적으로 읽어낼 수 있는 신호. 규칙 Matcher와 LLM 결과 검증기가 같이 쓴다.
//
// 세 가지를 본다.
//   1. 매물이 어느 카탈로그 모델인가(별칭 표). 판매자가 확정한 brand/model → 제목 → 설명 순.
//      설명은 검색 노출용으로 무관한 브랜드를 줄줄이 적는 매물이 많아 가장 낮은 신뢰로 본다.
//   2. 브랜드. 이름만 같은 다른 브랜드(골든구스 슈퍼스타, 알든 990 구두)를 거르는 데 쓴다.
//   3. 사지 말아야 할 매물의 명백한 표식 - 박스만 판매, 아동용, 옷·가방, 여러 켤레 일괄.
final class ListingSignals {

    enum Source {
        STRUCTURED, TITLE, DESCRIPTION, NONE
    }

    record Resolution(String modelKey, Source source, String matchedAlias) {
        static final Resolution NONE = new Resolution(null, Source.NONE, null);

        boolean isConfident() {
            return source == Source.STRUCTURED || source == Source.TITLE;
        }
    }

    // "박스만 판매" 류. "박스만 개봉/훼손/조금 까진"은 박스 상태 설명이라 제외한다.
    private static final Pattern BOX_ONLY = Pattern.compile(
            "(신발\\s*)?박스\\s*만\\s*(판매|팝|팔|있|드려|드림|입니다|이에요|이니|이라|임)");
    private static final Pattern KIDS = Pattern.compile("아동|키즈|유아|주니어|어린이|(?<!\\d)1[0-9]\\d\\s*(mm|사이즈|cm)");
    // 설명란의 아동용 정황. "아이"만으로는 "아이보리"에 걸리므로 조사·명사가 붙은 형태만.
    private static final Pattern KIDS_IN_DESCRIPTION = Pattern.compile("아이들이|아이가\s*신|아이\s*발|아이\s*신발|아이들\s*신");
    private static final Pattern APPAREL = Pattern.compile(
            "후드|집업|자켓|재킷|바람막이|백팩|가방|팬츠|바지|티셔츠|맨투맨|모자|양말|웜업|니트|셔츠|코트|패딩");
    private static final Pattern SANDAL_LIKE = Pattern.compile("슬리퍼|샌들|슬라이드|뮬(?![a-z가-힣])");
    // 여러 켤레를 번호 매겨 파는 매물. "1. 나이키 ... 2. 아식스 ..." 또는 "일괄/묶음".
    private static final Pattern BUNDLE = Pattern.compile("일괄|묶음\\s*판매|(^|\\s)1\\.\\s*\\S.*\\s2\\.\\s*\\S");

    // 원래 슬리퍼·샌들·클로그·부츠인 모델. 이 Goal에는 SANDAL_LIKE 표식이 부정 신호가 아니다.
    private static final Set<String> SANDAL_LIKE_MODELS =
            Set.of("tasman", "airmaxkoko", "boston", "classicclog", "hunteroriginal", "rockfishrain");

    private ListingSignals() {
    }

    static Resolution resolve(AuctionListing listing, ModelAliases aliases) {
        Optional<ModelAliases.Match> structured = aliases.find(join(listing.brand(), listing.model()));
        if (structured.isPresent()) {
            return new Resolution(structured.get().model().modelKey(), Source.STRUCTURED, structured.get().matchedAlias());
        }
        Optional<ModelAliases.Match> title = aliases.find(listing.title());
        if (title.isPresent()) {
            return new Resolution(title.get().model().modelKey(), Source.TITLE, title.get().matchedAlias());
        }
        Optional<ModelAliases.Match> description = aliases.find(listing.description());
        if (description.isPresent()) {
            return new Resolution(description.get().model().modelKey(), Source.DESCRIPTION, description.get().matchedAlias());
        }
        return Resolution.NONE;
    }

    // 판매자가 확정한 brand 필드 → 제목 순. 설명은 스팸이 많아 보지 않는다.
    static Optional<String> brandOf(AuctionListing listing) {
        Optional<String> structured = BrandAliases.canonical(listing.brand());
        if (structured.isPresent()) {
            return structured;
        }
        return BrandAliases.find(join(listing.model(), listing.title())).map(BrandAliases.Match::brand);
    }

    // 사지 말아야 할 매물이면 이유를 돌려준다. 없으면 empty.
    static Optional<String> hardNegative(AuctionListing listing, MatchGoal goal) {
        String primary = listing.primaryText();
        String all = listing.allText();
        if (BOX_ONLY.matcher(all).find()) {
            return Optional.of("박스만 판매하는 매물");
        }
        if (KIDS.matcher(primary).find() || KIDS_IN_DESCRIPTION.matcher(all).find()) {
            return Optional.of("아동용 매물");
        }
        if (APPAREL.matcher(primary).find()) {
            return Optional.of("신발이 아닌 품목(의류·가방)");
        }
        if (goal.modelKey() != null && !SANDAL_LIKE_MODELS.contains(goal.modelKey())
                && SANDAL_LIKE.matcher(primary).find()) {
            return Optional.of("슬리퍼·샌들 매물 - 요청 모델은 운동화");
        }
        Matcher bundle = BUNDLE.matcher(all);
        if (bundle.find()) {
            return Optional.of("여러 켤레 일괄 판매 매물");
        }
        return Optional.empty();
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + " " + b;
    }
}
