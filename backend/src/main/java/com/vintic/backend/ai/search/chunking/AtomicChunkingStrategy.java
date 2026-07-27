package com.vintic.backend.ai.search.chunking;

import com.vintic.backend.ai.search.document.DocumentChunk;
import com.vintic.backend.ai.search.document.SearchDocument;
import org.springframework.stereotype.Component;

import java.util.List;

// 상품처럼 짧은 문서는 억지로 쪼개지 않고 문서 전체를 chunk 하나로 유지한다.
// 실제 프로덕션에서 상품 검색에 사용하는 기본 전략.
@Component
public class AtomicChunkingStrategy implements ChunkingStrategy {

    @Override
    public List<DocumentChunk> chunk(SearchDocument document) {
        return List.of(new DocumentChunk(document.documentId(), 0, document.text()));
    }

    @Override
    public String name() {
        return "atomic";
    }
}
