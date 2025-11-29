package com.examprep.core.query;

import com.examprep.core.query.model.QueryAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Analyzes queries to extract keywords, entities, and determine query type.
 * Uses rule-based analysis (no LLM call) for efficiency.
 */
@Service
@Slf4j
public class QueryAnalyzer {
    
    private static final Pattern EXPLANATORY_PATTERNS = Pattern.compile(
        "(?i)(what is|what are|explain|describe|tell me about|define)"
    );
    
    private static final Pattern FACTUAL_PATTERNS = Pattern.compile(
        "(?i)(who|when|where|which|how many|how much)"
    );
    
    private static final Pattern COMPARATIVE_PATTERNS = Pattern.compile(
        "(?i)(compare|difference|similar|versus|vs|better|worse)"
    );
    
    private static final Pattern HOW_TO_PATTERNS = Pattern.compile(
        "(?i)(how to|how do|how can|steps|process|procedure)"
    );
    
    private static final Pattern ANALYTICAL_PATTERNS = Pattern.compile(
        "(?i)(why|analyze|evaluate|discuss|implications|consequences)"
    );
    
    private static final Pattern FOLLOW_UP_PATTERNS = Pattern.compile(
        "(?i)(it|this|that|the book|the author|who wrote|tell me more)"
    );
    
    /**
     * Analyze query without LLM call (rule-based).
     */
    public QueryAnalysis analyze(String question) {
        if (question == null || question.trim().isEmpty()) {
            return QueryAnalysis.builder()
                .queryType(QueryAnalysis.QueryType.EXPLANATORY)
                .keywords(List.of())
                .entities(List.of())
                .complexity(QueryAnalysis.Complexity.SIMPLE)
                .build();
        }
        
        String lowerQuestion = question.toLowerCase();
        
        // Determine query type
        QueryAnalysis.QueryType queryType = determineQueryType(lowerQuestion);
        
        // Extract keywords (simple approach: important words)
        List<String> keywords = extractKeywords(question);
        
        // Extract entities (capitalized words, technical terms)
        List<String> entities = extractEntities(question);
        
        // Determine complexity
        QueryAnalysis.Complexity complexity = determineComplexity(question, keywords.size());
        
        log.debug("Query analysis: type={}, keywords={}, entities={}, complexity={}",
            queryType, keywords, entities, complexity);
        
        return QueryAnalysis.builder()
            .queryType(queryType)
            .keywords(keywords)
            .entities(entities)
            .complexity(complexity)
            .build();
    }
    
    private QueryAnalysis.QueryType determineQueryType(String lowerQuestion) {
        if (FOLLOW_UP_PATTERNS.matcher(lowerQuestion).find()) {
            return QueryAnalysis.QueryType.FOLLOW_UP;
        }
        if (EXPLANATORY_PATTERNS.matcher(lowerQuestion).find()) {
            return QueryAnalysis.QueryType.EXPLANATORY;
        }
        if (HOW_TO_PATTERNS.matcher(lowerQuestion).find()) {
            return QueryAnalysis.QueryType.HOW_TO;
        }
        if (COMPARATIVE_PATTERNS.matcher(lowerQuestion).find()) {
            return QueryAnalysis.QueryType.COMPARATIVE;
        }
        if (ANALYTICAL_PATTERNS.matcher(lowerQuestion).find()) {
            return QueryAnalysis.QueryType.ANALYTICAL;
        }
        if (FACTUAL_PATTERNS.matcher(lowerQuestion).find()) {
            return QueryAnalysis.QueryType.FACTUAL;
        }
        return QueryAnalysis.QueryType.EXPLANATORY; // Default
    }
    
    private List<String> extractKeywords(String question) {
        // Remove common stop words
        List<String> stopWords = Arrays.asList(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "are", "was", "were", "be",
            "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "should", "could", "may", "might", "can", "this", "that",
            "these", "those", "what", "which", "who", "when", "where", "why", "how"
        );
        
        String[] words = question.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .split("\\s+");
        
        List<String> keywords = new ArrayList<>();
        for (String word : words) {
            if (word.length() > 2 && !stopWords.contains(word)) {
                keywords.add(word);
            }
        }
        
        // Limit to top 10 most important keywords
        return keywords.stream()
            .distinct()
            .limit(10)
            .toList();
    }
    
    private List<String> extractEntities(String question) {
        // Extract capitalized words and technical terms
        String[] words = question.split("\\s+");
        List<String> entities = new ArrayList<>();
        
        for (String word : words) {
            // Capitalized words (likely entities)
            if (word.length() > 1 && Character.isUpperCase(word.charAt(0))) {
                String cleaned = word.replaceAll("[^a-zA-Z0-9]", "");
                if (cleaned.length() > 1) {
                    entities.add(cleaned);
                }
            }
        }
        
        // Also look for common technical patterns (e.g., "Spring Boot", "JWT")
        Pattern techPattern = Pattern.compile("\\b([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)\\b");
        java.util.regex.Matcher matcher = techPattern.matcher(question);
        while (matcher.find()) {
            String techTerm = matcher.group(1);
            if (!entities.contains(techTerm)) {
                entities.add(techTerm);
            }
        }
        
        return entities.stream()
            .distinct()
            .limit(10)
            .toList();
    }
    
    private QueryAnalysis.Complexity determineComplexity(String question, int keywordCount) {
        int wordCount = question.split("\\s+").length;
        
        if (wordCount > 20 || keywordCount > 5) {
            return QueryAnalysis.Complexity.COMPLEX;
        }
        if (wordCount > 10 || keywordCount > 3) {
            return QueryAnalysis.Complexity.MEDIUM;
        }
        return QueryAnalysis.Complexity.SIMPLE;
    }
}

