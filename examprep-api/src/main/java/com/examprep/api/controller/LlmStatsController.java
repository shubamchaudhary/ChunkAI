package com.examprep.api.controller;

import com.examprep.llm.service.UnifiedLlmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmStatsController {
    
    private final UnifiedLlmService llmService;
    
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return ResponseEntity.ok(llmService.getStatistics());
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> stats = llmService.getStatistics();
        int totalProviders = (Integer) stats.get("totalProviders");
        boolean healthy = totalProviders > 0;
        
        return ResponseEntity.status(healthy ? 200 : 503).body(Map.of(
            "status", healthy ? "UP" : "DOWN",
            "totalProviders", totalProviders
        ));
    }
    
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testGeneration(@RequestBody(required = false) Map<String, String> request) {
        String prompt = request != null && request.containsKey("prompt") 
            ? request.get("prompt") 
            : "Say 'Hello, LLM router working!'";
        
        long startTime = System.currentTimeMillis();
        
        try {
            String response = llmService.generateContent(prompt);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "response", response,
                "durationMs", System.currentTimeMillis() - startTime
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }
}

