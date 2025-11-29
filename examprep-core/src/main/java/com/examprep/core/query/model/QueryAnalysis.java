package com.examprep.core.query.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QueryAnalysis {
    private QueryType queryType;
    private List<String> keywords;
    private List<String> entities;
    private Complexity complexity;
    
    public enum QueryType {
        EXPLANATORY,      // "What is...", "Explain..."
        FACTUAL,          // "Who wrote...", "When did..."
        COMPARATIVE,      // "Compare...", "Difference between..."
        HOW_TO,           // "How to...", "Steps to..."
        ANALYTICAL,       // "Why...", "Analyze..."
        FOLLOW_UP         // References previous conversation
    }
    
    public enum Complexity {
        SIMPLE,           // Single concept, straightforward
        MEDIUM,           // Multiple concepts, moderate detail
        COMPLEX           // Deep analysis, multiple sources needed
    }
}

