package com.examprep.llm.prompt;

public final class ExamAnswerPrompts {
    
    public static final String SYSTEM_PROMPT = """
        You are an expert exam preparation assistant. Your job is to help students 
        answer exam questions using ONLY the information provided in the context.
        
        IMPORTANT RULES:
        1. Use ONLY information from the provided context. Do not add external knowledge.
        2. If the context doesn't contain enough information, say so clearly.
        3. Structure your answers based on the marks allocated.
        4. Use proper formatting: headings, bullet points, chemical equations where appropriate.
        5. Always cite which source (slide/page) the information comes from.
        6. Write in exam-appropriate language - clear, concise, and academic.
        
        MARKS ALLOCATION GUIDE:
        - 1-2 marks: Brief definition or single point (2-3 sentences)
        - 3-5 marks: Short answer with key points (1 paragraph + bullet points)
        - 6-10 marks: Detailed answer with introduction, main content, conclusion
        - 10+ marks: Comprehensive essay-style with multiple sections
        """;
    
    public static String buildUserPrompt(
            String question,
            String context,
            Integer marks,
            String customFormat
    ) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("CONTEXT FROM YOUR UPLOADED DOCUMENTS:\n");
        prompt.append("=====================================\n");
        prompt.append(context);
        prompt.append("\n=====================================\n\n");
        
        prompt.append("QUESTION: ").append(question).append("\n\n");
        
        if (marks != null) {
            prompt.append("MARKS ALLOCATED: ").append(marks).append("\n\n");
            prompt.append("Please structure your answer appropriately for a ");
            prompt.append(marks).append("-mark question.\n\n");
        }
        
        if (customFormat != null && !customFormat.isBlank()) {
            prompt.append("ADDITIONAL FORMAT INSTRUCTIONS:\n");
            prompt.append(customFormat).append("\n\n");
        }
        
        prompt.append("Provide a well-structured answer using only the context above. ");
        prompt.append("Cite sources using [Source X] format.");
        
        return prompt.toString();
    }
    
    private ExamAnswerPrompts() {}
}

