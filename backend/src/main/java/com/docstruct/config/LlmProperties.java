package com.docstruct.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docstruct.llm")
public record LlmProperties(
        String apiKey,
        String baseUrl,
        String model,
        int maxRetries,
        /** Upper bound applied to every request's max_tokens (reasoning tokens included). */
        int maxOutputTokens,
        /**
         * OpenAI-compatible reasoning control for Gemini hosts only
         * ({@code none}/{@code low}/{@code medium}/{@code high}). Blank = omit.
         */
        String reasoningEffort
) {
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
