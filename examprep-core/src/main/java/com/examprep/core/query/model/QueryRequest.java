package com.examprep.core.query.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class QueryRequest {
    private UUID userId;
    private UUID chatId;
    private String question;
    private Integer marks;
    private String formatInstructions;
    private List<UUID> documentIds;
    private boolean useCrossChat;
    private List<ChatMessage> chatHistory;
    
    @Data
    @Builder
    public static class ChatMessage {
        private String role; // "user" or "assistant"
        private String content;
    }
}

