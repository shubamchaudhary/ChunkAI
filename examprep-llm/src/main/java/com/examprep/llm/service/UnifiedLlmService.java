package com.examprep.llm.service;

import com.examprep.llm.router.WeightedLlmRouter;
import com.examprep.llm.router.WeightedLlmRouter.LlmRouterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class UnifiedLlmService {
    
    private final WeightedLlmRouter router;
    
    public String generateContent(String prompt) {
        try {
            return router.generateContent(prompt);
        } catch (LlmRouterException e) {
            log.error("Failed to generate content after trying {} providers", 
                e.getAttemptedProviders().size(), e);
            throw new RuntimeException("Failed to generate content: " + e.getMessage(), e);
        }
    }
    
    public String generateContent(String prompt, String model) {
        try {
            return router.generateContent(prompt, model);
        } catch (LlmRouterException e) {
            log.error("Failed to generate content after trying {} providers", 
                e.getAttemptedProviders().size(), e);
            throw new RuntimeException("Failed to generate content: " + e.getMessage(), e);
        }
    }
    
    public String generateJsonContent(String prompt) {
        String jsonPrompt = prompt + "\n\nRespond with valid JSON only. No markdown, no code blocks.";
        return generateContent(jsonPrompt);
    }
    
    public Map<String, Object> getStatistics() {
        return router.getStatistics();
    }
}

