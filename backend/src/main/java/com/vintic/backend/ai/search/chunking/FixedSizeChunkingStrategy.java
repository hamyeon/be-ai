package com.vintic.backend.ai.search.chunking;

import com.vintic.backend.ai.search.document.DocumentChunk;
import com.vintic.backend.ai.search.document.SearchDocument;

import java.util.ArrayList;
import java.util.List;

// 긴 설명/가이드 문서를 위한 실험용 전략. 글자 수 기준으로 겹치게 잘라 문맥이 끊기는 걸 완화한다.
// 상품 데이터처럼 짧은 문서에는 쓰지 않는다 (그런 경우는 AtomicChunkingStrategy 사용).
public class FixedSizeChunkingStrategy implements ChunkingStrategy {

    private final int chunkSize;
    private final int overlap;

    public FixedSizeChunkingStrategy(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize는 0보다 커야 합니다.");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap은 0 이상 chunkSize 미만이어야 합니다.");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<DocumentChunk> chunk(SearchDocument document) {
        String text = document.text();
        List<DocumentChunk> chunks = new ArrayList<>();

        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(new DocumentChunk(document.documentId(), index++, text.substring(start, end)));
            if (end == text.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }

    @Override
    public String name() {
        return "fixed-size(%d,%d)".formatted(chunkSize, overlap);
    }
}
