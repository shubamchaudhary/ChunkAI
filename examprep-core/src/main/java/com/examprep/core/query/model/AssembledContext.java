package com.examprep.core.query.model;

import com.examprep.data.repository.DocumentChunkRepositoryCustom;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AssembledContext {
    private String contextText;
    private List<DocumentChunkRepositoryCustom.ScoredChunk> allChunks;
    private int chunkCount;
    private int totalTokens;
    
    public static AssembledContext empty() {
        return AssembledContext.builder()
            .contextText("")
            .allChunks(List.of())
            .chunkCount(0)
            .totalTokens(0)
            .build();
    }
}

