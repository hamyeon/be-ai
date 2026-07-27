package com.vintic.backend.ai.search.document;

// 임베딩 대상 텍스트 조각. 상품처럼 짧은 문서는 chunkIndex 0 하나만 존재하는 atomic chunk가 된다.
public record DocumentChunk(
        String documentId,
        int chunkIndex,
        String text
) {
    public String chunkId() {
        return documentId + "#" + chunkIndex;
    }
}
