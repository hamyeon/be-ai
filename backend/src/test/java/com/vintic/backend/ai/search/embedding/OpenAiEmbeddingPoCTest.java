package com.vintic.backend.ai.search.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vintic.backend.ai.search.document.SearchDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 OpenAI Embeddings API를 호출하는 PoC. OPENAI_API_KEY가 설정된 환경에서만 실행된다.
 * (이 세션에는 키가 없어 직접 실행/검증하지 못했음 - 실제 키가 있는 환경에서 한 번 돌려서
 * 아래 TEST_QUERIES에 대한 상위 결과가 기대와 맞는지 눈으로 확인해봐야 한다.)
 *
 * 목적: (1) 임베딩이 정상적으로 생성되는지, (2) 소규모 데이터에서 벡터 유사도 검색이
 * 문자열 검색보다 실익이 있는지 판단하기 위한 근거 자료를 만드는 것.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAiEmbeddingPoCTest {

    // 테스트 질의와 "문자열 검색으로는 못 찾지만 벡터 검색이면 찾아야 하는" 기대 결과.
    // (질의 텍스트에 등장하지 않는 동의어/의미로 표현해 문자열 검색의 한계를 드러내도록 구성)
    private static final List<TestQuery> TEST_QUERIES = List.of(
            new TestQuery("발볼 넓은 편한 신발 찾아요", "product-3"),           // "편한" -> "쿠셔닝","착화감" 설명과 매칭 기대
            new TestQuery("박스 있는 새 신발", "product-1"),                   // DS + 박스 포함
            new TestQuery("아직 한 번도 안 신은 신발", "product-1"),           // "미착용"의 다른 표현
            new TestQuery("사용감 있고 저렴한 신발", "product-2"),
            new TestQuery("하얀색 계열 신발", "product-5"),
            new TestQuery("빨간색 포인트 신발", "product-4"),
            new TestQuery("가벼운 러닝화", "product-3"),
            new TestQuery("클래식한 가죽 스니커즈", "product-2"),
            new TestQuery("여름에 신기 좋은 신발", "product-5"),
            new TestQuery("발이 편안한 쿠션 좋은 신발", "product-3")
    );

    private static final List<SearchDocument> SAMPLE_DOCUMENTS = List.of(
            new SearchDocument("product-1", "PRODUCT", 1L,
                    "Nike Dunk Low Panda\n미착용 새상품입니다. 박스 포함이고 태그도 그대로 있어요.\n사이즈 270\n상태 등급 DS",
                    Map.of("brand", "Nike", "price", 220000)),
            new SearchDocument("product-2", "PRODUCT", 2L,
                    "Jordan 1 Retro High Lost and Found\n실착 여러 번 해서 사용감 좀 있어요. 그만큼 저렴하게 팔아요. 가죽이라 클래식한 느낌이에요.\n사이즈 270\n상태 등급 B",
                    Map.of("brand", "Jordan", "price", 180000)),
            new SearchDocument("product-3", "PRODUCT", 3L,
                    "New Balance 993 Gray\n쿠셔닝이 정말 좋고 발볼도 넓게 나와서 오래 신어도 편해요. 가볍게 러닝할 때도 좋아요.\n사이즈 265\n상태 등급 A",
                    Map.of("brand", "New Balance", "price", 175000)),
            new SearchDocument("product-4", "PRODUCT", 4L,
                    "Nike Air Max 95 Neon\n레드 포인트 컬러가 강렬한 모델이에요. 눈에 띄는 스타일 원하시는 분께 추천해요.\n사이즈 270\n상태 등급 B",
                    Map.of("brand", "Nike", "price", 195000)),
            new SearchDocument("product-5", "PRODUCT", 5L,
                    "Adidas Samba OG Cloud White\n화이트 계열이라 여름철 코디하기 좋고 통풍도 잘 돼요.\n사이즈 265\n상태 등급 A",
                    Map.of("brand", "Adidas", "price", 210000))
    );

    @Test
    void 임베딩_생성_저장_후_벡터_검색과_문자열_검색을_비교한다() throws Exception {
        EmbeddingClient embeddingClient = new OpenAiEmbeddingClient(new ObjectMapper());
        InMemoryEmbeddingStore embeddingStore = new InMemoryEmbeddingStore();

        for (SearchDocument document : SAMPLE_DOCUMENTS) {
            float[] vector = embeddingClient.embed(document.text());
            assertThat(vector).isNotEmpty();
            embeddingStore.save(new EmbeddingVector(document.documentId(), vector));
        }
        assertThat(embeddingStore.size()).isEqualTo(SAMPLE_DOCUMENTS.size());

        int hitAt1 = 0;
        int hitAt3 = 0;
        double reciprocalRankSum = 0.0;

        System.out.println("=".repeat(100));
        for (TestQuery testQuery : TEST_QUERIES) {
            float[] queryVector = embeddingClient.embed(testQuery.query());
            List<ScoredChunk> vectorResult = embeddingStore.search(queryVector, 3);
            List<String> stringResult = stringSearch(testQuery.query());

            int rank = rankOf(vectorResult, testQuery.expectedTopDocumentId()); // 1,2,3 or -1
            boolean top1 = rank == 1;
            boolean top3 = rank != -1;
            if (top1) {
                hitAt1++;
            }
            if (top3) {
                hitAt3++;
            }
            reciprocalRankSum += (rank == -1) ? 0.0 : (1.0 / rank);

            System.out.printf("질의: \"%s\" (기대: %s)%n", testQuery.query(), testQuery.expectedTopDocumentId());
            for (int i = 0; i < vectorResult.size(); i++) {
                ScoredChunk scored = vectorResult.get(i);
                System.out.printf("  %d위: %s (score=%.3f)%s%n",
                        i + 1, scored.chunkId(), scored.score(),
                        scored.chunkId().equals(testQuery.expectedTopDocumentId()) ? "  <- 기대 상품" : "");
            }
            System.out.printf("  문자열 검색 결과: %s | Top-1=%s Top-3=%s%n",
                    stringResult.isEmpty() ? "없음(문자열 매칭 실패)" : stringResult, top1, top3);
            System.out.println("-".repeat(100));
        }

        double mrr = reciprocalRankSum / TEST_QUERIES.size();
        System.out.printf("Hit@1: %d/%d (%.0f%%)%n", hitAt1, TEST_QUERIES.size(), 100.0 * hitAt1 / TEST_QUERIES.size());
        System.out.printf("Hit@3: %d/%d (%.0f%%)%n", hitAt3, TEST_QUERIES.size(), 100.0 * hitAt3 / TEST_QUERIES.size());
        System.out.printf("MRR: %.3f%n", mrr);
        System.out.println("=".repeat(100));
    }

    // vectorResult 안에서 expectedDocumentId의 순위(1부터 시작)를 반환, 없으면 -1
    private int rankOf(List<ScoredChunk> vectorResult, String expectedDocumentId) {
        for (int i = 0; i < vectorResult.size(); i++) {
            if (vectorResult.get(i).chunkId().equals(expectedDocumentId)) {
                return i + 1;
            }
        }
        return -1;
    }

    // 지금 실제 서비스에 쓰는 방식과 동일한 수준(단순 부분 문자열 포함 여부)의 비교 기준선
    private List<String> stringSearch(String query) {
        return SAMPLE_DOCUMENTS.stream()
                .filter(document -> document.text().contains(query) || containsAnyToken(document.text(), query))
                .map(SearchDocument::documentId)
                .toList();
    }

    private boolean containsAnyToken(String text, String query) {
        for (String token : query.split("\\s+")) {
            if (token.length() > 1 && text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private record TestQuery(String query, String expectedTopDocumentId) {
    }
}
