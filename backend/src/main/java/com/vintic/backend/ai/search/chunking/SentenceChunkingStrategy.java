package com.vintic.backend.ai.search.chunking;

import com.vintic.backend.ai.search.document.DocumentChunk;
import com.vintic.backend.ai.search.document.SearchDocument;

import java.util.ArrayList;
import java.util.List;

// 긴 설명/가이드 문서를 위한 실험용 전략. 문장 경계를 지켜서 문맥이 중간에 끊기지 않게 묶는다.
public class SentenceChunkingStrategy implements ChunkingStrategy {

    private final int maxChunkLength;

    public SentenceChunkingStrategy(int maxChunkLength) {
        if (maxChunkLength <= 0) {
            throw new IllegalArgumentException("maxChunkLength는 0보다 커야 합니다.");
        }
        this.maxChunkLength = maxChunkLength;
    }

    @Override
    public List<DocumentChunk> chunk(SearchDocument document) {
        String[] sentences = document.text().split("(?<=[.!?\\n])\\s*");
        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int index = 0;

        for (String sentence : sentences) {
            if (sentence.isBlank()) {
                continue;
            }
            if (!current.isEmpty() && current.length() + sentence.length() > maxChunkLength) {
                chunks.add(new DocumentChunk(document.documentId(), index++, current.toString().trim()));
                current.setLength(0);
            }
            current.append(sentence).append(" ");
        }
        if (!current.isEmpty()) {
            chunks.add(new DocumentChunk(document.documentId(), index, current.toString().trim()));
        }
        return chunks;
    }

    @Override
    public String name() {
        return "sentence(%d)".formatted(maxChunkLength);
    }
}
