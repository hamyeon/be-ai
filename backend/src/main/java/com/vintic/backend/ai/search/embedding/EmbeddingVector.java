package com.vintic.backend.ai.search.embedding;

import java.util.Arrays;
import java.util.Objects;

public record EmbeddingVector(String chunkId, float[] vector) {

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EmbeddingVector other)) {
            return false;
        }
        return Objects.equals(chunkId, other.chunkId) && Arrays.equals(vector, other.vector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkId, Arrays.hashCode(vector));
    }

    @Override
    public String toString() {
        return "EmbeddingVector[chunkId=%s, dimension=%d]".formatted(chunkId, vector.length);
    }
}
