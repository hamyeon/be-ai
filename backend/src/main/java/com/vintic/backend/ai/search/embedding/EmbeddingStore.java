package com.vintic.backend.ai.search.embedding;

import java.util.List;

// 임베딩 저장/유사도 검색 경계. 지금은 InMemoryEmbeddingStore만 있고,
// 향후 Redis Vector 도입이 결정되면 RedisEmbeddingStore로 교체 가능하도록 구현을 분리해둔다.
public interface EmbeddingStore {

    void save(EmbeddingVector embedding);

    List<ScoredChunk> search(float[] queryVector, int topK);
}
