package com.vintic.backend.ai.purchase.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAliasesTest {

    private final ModelAliases aliases = new ModelAliases();

    @ParameterizedTest
    @CsvSource({
            "뉴발 990 A급, nb990",
            "뉴발란스990v6 그레이, nb990",
            "NB990 275, nb990",
            "990v6 사고 싶어요, nb990",
            "덩크로우 판다, dunklow",
            "덩크 로우 판다, dunklow",
            "덩크, dunklow",
            "덩크하이 판다, dunkhigh",
            "조던1 시카고, jordan1",
            "에어조던 3 시멘트, jordan3",
            "XT-6 살로몬, xt6",
            "닥터마틴 1461 3홀, dm1461",
            "닥마 1460 8홀, dm1460",
            "젤 카야노 14, gelkayano14",
            "척70 하이, chuck70",
            "락피쉬 첼시 레인부츠, rockfishrain",
            "Air Force 1 '07, airforce1"
    })
    void 표기가_달라도_같은_모델_키로_접는다(String text, String expectedKey) {
        assertThat(aliases.find(text)).map(m -> m.model().modelKey()).contains(expectedKey);
    }

    @Test
    void 긴_별칭이_짧은_별칭보다_먼저_잡힌다() {
        // "덩크하이"가 "덩크"(=dunklow)에 먹히면 하이를 로우로 사게 된다.
        assertThat(aliases.find("나이키 덩크하이 265")).map(m -> m.model().modelKey()).contains("dunkhigh");
        assertThat(aliases.find("뉴발 993 그레이")).map(m -> m.model().modelKey()).contains("nb993");
    }

    @ParameterizedTest
    @CsvSource({
            "990 사이즈 270",
            "1461 15만원",
            "530 사이즈",
            "15만원 이하로 신발 하나",
            "조던 아무거나"
    })
    void 브랜드_없는_숫자나_모호한_표기는_모델로_잡지_않는다(String text) {
        assertThat(aliases.find(text)).isEmpty();
    }

    @Test
    void 매칭된_별칭_원문을_함께_돌려준다() {
        ModelAliases.Match match = aliases.find("뉴발-990 그레이").orElseThrow();

        assertThat(match.matchedAlias()).isEqualTo("뉴발 990");
        assertThat(match.model().fullName()).isEqualTo("New Balance 990");
    }

    @Test
    void 카탈로그_키는_시세_CSV의_model_key와_정확히_같다() throws IOException {
        // 파서가 고른 키로 바로 시세를 찾을 수 있어야 한다. 한쪽에만 있는 키가 생기면 Agent가 그 모델을 영영 못 본다.
        Set<String> priceKeys = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("data/used_market_prices.csv").getInputStream(), StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                priceKeys.add(line.split(",")[1].trim());
            }
        }
        Set<String> catalogKeys = new HashSet<>();
        aliases.catalog().forEach(model -> catalogKeys.add(model.modelKey()));

        assertThat(catalogKeys).containsExactlyInAnyOrderElementsOf(priceKeys);
    }

    @Test
    void 카탈로그_브랜드는_브랜드_별칭_표와_맞는다() {
        aliases.catalog().forEach(model ->
                assertThat(BrandAliases.canonical(model.brand()))
                        .as("브랜드 별칭 표에 없는 브랜드: " + model.brand()).contains(model.brand()));
    }
}
