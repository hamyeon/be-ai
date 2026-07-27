package com.vintic.backend.ai.search.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class InMemoryEmbeddingStoreTest {

    private final InMemoryEmbeddingStore sut = new InMemoryEmbeddingStore();

    @Test
    void 동일한_벡터의_유사도는_1이다() {
        sut.save(new EmbeddingVector("a", new float[]{1f, 0f, 0f}));

        List<ScoredChunk> result = sut.search(new float[]{1f, 0f, 0f}, 1);

        assertThat(result.get(0).chunkId()).isEqualTo("a");
        assertThat(result.get(0).score()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void 직교하는_벡터의_유사도는_0이다() {
        sut.save(new EmbeddingVector("a", new float[]{1f, 0f}));

        List<ScoredChunk> result = sut.search(new float[]{0f, 1f}, 1);

        assertThat(result.get(0).score()).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void 반대_방향_벡터의_유사도는_마이너스1이다() {
        sut.save(new EmbeddingVector("a", new float[]{1f, 0f}));

        List<ScoredChunk> result = sut.search(new float[]{-1f, 0f}, 1);

        assertThat(result.get(0).score()).isCloseTo(-1.0, within(1e-6));
    }

    @Test
    void topK만큼_유사도_높은_순으로_정렬해서_반환한다() {
        sut.save(new EmbeddingVector("low", new float[]{0.1f, 1f}));
        sut.save(new EmbeddingVector("high", new float[]{1f, 0.05f}));
        sut.save(new EmbeddingVector("mid", new float[]{0.6f, 0.5f}));

        List<ScoredChunk> result = sut.search(new float[]{1f, 0f}, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo("high");
        assertThat(result.get(0).score()).isGreaterThan(result.get(1).score());
    }

    @Test
    void 벡터_차원이_다르면_예외를_던진다() {
        sut.save(new EmbeddingVector("a", new float[]{1f, 0f, 0f}));

        assertThatThrownBy(() -> sut.search(new float[]{1f, 0f}, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
