package com.examprep.llm.service;

import com.examprep.llm.client.GeminiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {
    
    private final GeminiClient geminiClient;
    
    /**
     * Generate embedding for a single text
     */
    public float[] generateEmbedding(String text) {
        log.debug("Generating embedding for text of length: {}", text.length());
        return geminiClient.generateEmbedding(text);
    }
    
    /**
     * Generate embeddings for multiple texts (batch processing)
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        log.info("Generating embeddings for {} texts", texts.size());
        
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(generateEmbedding(text));
            
            // Rate limiting - simple delay between requests
            try {
                Thread.sleep(100); // 100ms between requests
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        return embeddings;
    }
    
    /**
     * Convert float array to pgvector format string
     */
    public String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            sb.append(embedding[i]);
            if (i < embedding.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}

