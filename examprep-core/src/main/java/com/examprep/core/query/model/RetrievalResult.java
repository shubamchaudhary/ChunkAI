package com.examprep.core.query.model;

import com.examprep.data.repository.DocumentChunkRepositoryCustom;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class RetrievalResult {
    private List<DocumentChunkRepositoryCustom.ScoredChunk> chunks;
    private int documentsMatched;
    private int totalChunksSearched;
    
    public static RetrievalResult empty() {
        return RetrievalResult.builder()
            .chunks(List.of())
            .documentsMatched(0)
            .totalChunksSearched(0)
            .build();
    }
}

