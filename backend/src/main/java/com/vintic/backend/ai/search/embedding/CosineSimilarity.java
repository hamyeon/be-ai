package com.vintic.backend.ai.search.embedding;

// 코사인 유사도. InMemoryEmbeddingStore의 private 메서드로 있던 것을 꺼냈다 -
// 추천(유저 벡터 vs 상품 벡터)에서도 같은 계산이 필요한데 구현이 두 벌이면 어긋난다.
public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    public static double between(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("벡터 차원이 서로 다릅니다: %d vs %d".formatted(a.length, b.length));
        }

        double dotProduct = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        // 영벡터와는 방향을 비교할 수 없다. 유사도 0으로 본다.
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
