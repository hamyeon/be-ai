package com.vintic.backend.ai.search.embedding;

public interface EmbeddingClient {

    float[] embed(String text);
}
