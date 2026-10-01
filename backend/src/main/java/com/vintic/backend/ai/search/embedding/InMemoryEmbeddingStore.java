package com.vintic.backend.ai.search.embedding;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// Redis 없이 벡터 검색 PoC를 검증하기 위한 인메모리 구현체. 소규모 데이터에서만 적합하다
// (매 검색마다 저장된 벡터 전체와 코사인 유사도를 계산하는 O(n) 브루트포스 방식).
@Component
public class InMemoryEmbeddingStore implements EmbeddingStore {

    private final List<EmbeddingVector> store = new CopyOnWriteArrayList<>();

    @Override
    public void save(EmbeddingVector embedding) {
        store.add(embedding);
    }

    @Override
    public List<ScoredChunk> search(float[] queryVector, int topK) {
        return store.stream()
                .map(embedding -> new ScoredChunk(embedding.chunkId(),
                        CosineSimilarity.between(queryVector, embedding.vector())))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    public int size() {
        return store.size();
    }
}
