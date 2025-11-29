package com.examprep.llm.provider;

public interface ProviderClient {
    
    String generateContent(String prompt, String apiKey) throws ProviderException;
    
    String generateContent(String prompt, String apiKey, String model) throws ProviderException;
    
    LlmProvider getProvider();
    
    String getDefaultModel();
    
    default boolean supportsFeature(ProviderFeature feature) {
        return false;
    }
    
    enum ProviderFeature {
        STREAMING,
        FUNCTION_CALLING,
        VISION,
        JSON_MODE,
        EMBEDDINGS
    }
    
    class ProviderException extends RuntimeException {
        private final boolean retryable;
        private final int statusCode;
        private final LlmProvider provider;
        
        public ProviderException(String message, LlmProvider provider, int statusCode, boolean retryable) {
            super(message);
            this.provider = provider;
            this.statusCode = statusCode;
            this.retryable = retryable;
        }
        
        public ProviderException(String message, LlmProvider provider, int statusCode, boolean retryable, Throwable cause) {
            super(message, cause);
            this.provider = provider;
            this.statusCode = statusCode;
            this.retryable = retryable;
        }
        
        public boolean isRetryable() { return retryable; }
        public int getStatusCode() { return statusCode; }
        public LlmProvider getProvider() { return provider; }
        public boolean isRateLimited() { return statusCode == 429; }
        public boolean isAuthError() { return statusCode == 401 || statusCode == 403; }
    }
}

