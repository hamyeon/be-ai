package com.vintic.backend.ai.search.chunking;

import com.vintic.backend.ai.search.document.DocumentChunk;
import com.vintic.backend.ai.search.document.SearchDocument;

import java.util.List;

public interface ChunkingStrategy {

    List<DocumentChunk> chunk(SearchDocument document);

    String name();
}
