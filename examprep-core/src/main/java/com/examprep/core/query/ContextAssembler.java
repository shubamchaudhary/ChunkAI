package com.examprep.core.query;

import com.examprep.common.util.TokenCounter;
import com.examprep.core.query.model.AssembledContext;
import com.examprep.core.query.model.QueryAnalysis;
import com.examprep.data.repository.DocumentChunkRepositoryCustom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Assembles retrieved chunks into a coherent context string.
 * Ensures context fits within token budget.
 */
@Service
@Slf4j
public class ContextAssembler {
    
    /**
     * Assemble context from chunks, fitting within token budget.
     */
    public AssembledContext assemble(
            List<DocumentChunkRepositoryCustom.ScoredChunk> chunks,
            QueryAnalysis analysis,
            int targetChunks,
            int maxTokens) {
        
        long startTime = System.currentTimeMillis();
        
        log.info("[CONTEXT_ASSEMBLER] Starting context assembly | inputChunks={} | targetChunks={} | maxTokens={}", 
            chunks.size(), targetChunks, maxTokens);
        
        if (chunks.isEmpty()) {
            log.warn("[CONTEXT_ASSEMBLER] No chunks provided, returning empty context");
            return AssembledContext.empty();
        }
        
        // Select chunks that fit within token budget
        long selectionStartTime = System.currentTimeMillis();
        List<DocumentChunkRepositoryCustom.ScoredChunk> selectedChunks = 
            selectChunksWithinBudget(chunks, targetChunks, maxTokens);
        long selectionDuration = System.currentTimeMillis() - selectionStartTime;
        
        log.info("[CONTEXT_ASSEMBLER] Chunks selected | inputChunks={} | selectedChunks={} | selectionDurationMs={}", 
            chunks.size(), selectedChunks.size(), selectionDuration);
        
        // Build context string with source markers
        long buildStartTime = System.currentTimeMillis();
        StringBuilder contextBuilder = new StringBuilder();
        int totalTokens = 0;
        int chunksAdded = 0;
        
        for (int i = 0; i < selectedChunks.size(); i++) {
            DocumentChunkRepositoryCustom.ScoredChunk scoredChunk = selectedChunks.get(i);
            String content = scoredChunk.getContent();
            int chunkTokens = TokenCounter.countTokens(content);
            
            // Check if adding this chunk would exceed budget
            if (totalTokens + chunkTokens > maxTokens && i > 0) {
                log.info("[CONTEXT_ASSEMBLER] Token budget reached | chunksAdded={} | totalTokens={} | maxTokens={} | chunkIndex={}", 
                    chunksAdded, totalTokens, maxTokens, i);
                break;
            }
            
            // Format: [Source X: filename, Page Y]
            String sourceMarker = String.format(
                "[Source %d: %s",
                i + 1,
                scoredChunk.getFileName()
            );
            
            if (scoredChunk.getSlideNumber() != null) {
                sourceMarker += ", Slide " + scoredChunk.getSlideNumber();
            } else if (scoredChunk.getPageNumber() != null) {
                sourceMarker += ", Page " + scoredChunk.getPageNumber();
            }
            sourceMarker += "]\n";
            
            contextBuilder.append(sourceMarker);
            contextBuilder.append(content);
            contextBuilder.append("\n\n");
            
            totalTokens += chunkTokens;
            chunksAdded++;
        }
        
        String contextText = contextBuilder.toString().trim();
        long buildDuration = System.currentTimeMillis() - buildStartTime;
        long totalDuration = System.currentTimeMillis() - startTime;
        
        log.info("[CONTEXT_ASSEMBLER] Context assembled successfully | totalDurationMs={} | selectedChunks={} | chunksAdded={} | totalTokens={} | contextLength={} | buildDurationMs={}", 
            totalDuration, selectedChunks.size(), chunksAdded, totalTokens, contextText.length(), buildDuration);
        
        return AssembledContext.builder()
            .contextText(contextText)
            .allChunks(selectedChunks)
            .chunkCount(selectedChunks.size())
            .totalTokens(totalTokens)
            .build();
    }
    
    private List<DocumentChunkRepositoryCustom.ScoredChunk> selectChunksWithinBudget(
            List<DocumentChunkRepositoryCustom.ScoredChunk> chunks,
            int targetChunks,
            int maxTokens) {
        
        List<DocumentChunkRepositoryCustom.ScoredChunk> selected = new java.util.ArrayList<>();
        int currentTokens = 0;
        
        // Take chunks up to target count or token limit
        for (DocumentChunkRepositoryCustom.ScoredChunk chunk : chunks) {
            if (selected.size() >= targetChunks) {
                break;
            }
            
            int chunkTokens = TokenCounter.countTokens(chunk.getContent());
            
            // Reserve some tokens for formatting and system prompts
            int reservedTokens = 1000;
            if (currentTokens + chunkTokens + reservedTokens > maxTokens) {
                break;
            }
            
            selected.add(chunk);
            currentTokens += chunkTokens;
        }
        
        return selected;
    }
}

