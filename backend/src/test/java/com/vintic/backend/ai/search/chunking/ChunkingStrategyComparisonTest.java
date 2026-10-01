package com.vintic.backend.ai.search.chunking;

import com.vintic.backend.ai.search.document.DocumentChunk;
import com.vintic.backend.ai.search.document.SearchDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// 짧은 상품 데이터는 atomic으로 유지하고, 긴 가이드 문서(실제 데이터가 아직 없어 합성 예시 사용)에만
// chunking 전략을 비교 적용해본다는 결정을 코드로 검증하는 실험 테스트.
class ChunkingStrategyComparisonTest {

    private static final String SNEAKER_CARE_GUIDE = """
            신발을 오래 신으려면 몇 가지 관리 요령이 필요합니다. 첫째, 착용 후에는 바로 신발장에 넣지 말고 통풍이 잘 되는 곳에서 습기를 제거해야 합니다. 스웨이드나 누벅 소재는 물에 약하기 때문에 전용 브러시로 먼지를 털어내는 것이 좋습니다.
            둘째, 미드솔이 노랗게 변색되는 것을 막으려면 직사광선을 피해서 보관해야 합니다. 특히 에어맥스나 덩크처럼 흰색 미드솔을 가진 모델은 자외선에 매우 취약합니다. 신발 보관용 박스에 넣어두면 변색 속도를 늦출 수 있습니다.
            셋째, 정기적으로 클리너와 발수 스프레이를 사용하면 오염을 예방할 수 있습니다. 가죽 소재는 전용 크림을 발라 갈라짐을 방지하는 것이 좋고, 메시 소재는 물세탁이 가능한 경우가 많으니 라벨을 확인해야 합니다.
            넷째, 신발끈은 주기적으로 교체해주는 것이 위생과 외관 모두에 좋습니다. 끈이 낡으면 신발 전체의 인상이 낡아 보이기 때문입니다.
            다섯째, 여러 켤레를 번갈아 신으면 한 켤레에 가해지는 하중과 습기를 분산시켜 밑창 수명을 늘릴 수 있습니다.
            """;

    @Test
    void 짧은_상품_문서는_atomic_전략으로_한_개의_chunk만_생성된다() {
        SearchDocument shortProductDocument = new SearchDocument(
                "product-1", "PRODUCT", 1L,
                "Nike Dunk Low Panda\n박스 없이 신발만 있어요\n사이즈 270",
                Map.of()
        );

        List<DocumentChunk> chunks = new AtomicChunkingStrategy().chunk(shortProductDocument);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(shortProductDocument.text());
    }

    @Test
    void 긴_가이드_문서는_전략에_따라_chunk_수와_경계가_달라진다() {
        SearchDocument guideDocument = new SearchDocument(
                "guide-sneaker-care", "GUIDE", null, SNEAKER_CARE_GUIDE, Map.of()
        );

        List<DocumentChunk> fixedSizeChunks = new FixedSizeChunkingStrategy(200, 30).chunk(guideDocument);
        List<DocumentChunk> sentenceChunks = new SentenceChunkingStrategy(200).chunk(guideDocument);

        // 둘 다 여러 개로 쪼개지긴 하지만, 문장 경계 전략은 각 chunk가 반드시 문장으로 끝난다
        assertThat(fixedSizeChunks.size()).isGreaterThan(1);
        assertThat(sentenceChunks.size()).isGreaterThan(1);

        for (DocumentChunk chunk : sentenceChunks) {
            String trimmed = chunk.text().trim();
            assertThat(trimmed).matches(".*[.!?]$");
        }

        // fixed-size는 글자 수로만 자르기 때문에 문장 중간에서 끊기는 chunk가 실제로 생긴다는 것을 확인
        boolean hasMidSentenceCut = fixedSizeChunks.stream()
                .anyMatch(chunk -> !chunk.text().trim().matches(".*[.!?]$"));
        assertThat(hasMidSentenceCut).isTrue();
    }
}
