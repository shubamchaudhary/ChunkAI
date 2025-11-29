package com.examprep.core.query.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QueryResult {
    private String answer;
    private List<Source> sources;
    private int chunksUsed;
    private int llmCallsUsed;
    private String processingMode; // "single-call", "map-reduce", "cached"
    private boolean cacheHit;
    
    public QueryResult withCacheHit(boolean cacheHit) {
        this.cacheHit = cacheHit;
        return this;
    }
    
    @Data
    @Builder
    public static class Source {
        private java.util.UUID documentId;
        private String fileName;
        private Integer pageNumber;
        private Integer slideNumber;
        private String excerpt;
    }
}

