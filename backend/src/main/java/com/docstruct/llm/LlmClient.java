package com.docstruct.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.docstruct.config.LlmProperties;
import com.docstruct.exception.AiServiceException;
import com.docstruct.exception.ExtractionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin client over an OpenAI-compatible chat-completions API (OpenRouter,
 * Google AI Studio, Groq, ...). Handles JSON response mode, vision inputs,
 * retries with linear backoff, and markdown-fence stripping.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final double DEFAULT_TEMPERATURE = 0.1;

    private final RestClient restClient;
    private final LlmProperties properties;
    private final ObjectMapper objectMapper;

    public LlmClient(RestClient llmRestClient, LlmProperties properties, ObjectMapper objectMapper) {
        this.restClient = llmRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record ImageData(String mimeType, String base64) {
    }

    /** Sends a prompt expecting a JSON object back; parses and returns it. */
    public JsonNode callJson(String prompt, ImageData imageData, int maxTokens) {
        String text = callWithRetries(prompt, imageData, DEFAULT_TEMPERATURE, maxTokens, true);
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new ExtractionException("AI returned invalid response format", e.getMessage());
        }
    }

    /** Sends a prompt and returns the raw text response (used for summaries). */
    public String callText(String prompt, double temperature, int maxTokens) {
        return callWithRetries(prompt, null, temperature, maxTokens, false);
    }

    public record LlmStatus(boolean configured, String provider, String model) {
    }

    /** Reports configuration status without making an API call (used by health checks). */
    public LlmStatus getStatus() {
        String provider = java.net.URI.create(properties.baseUrl()).getHost();
        return new LlmStatus(properties.isConfigured(), provider, properties.model());
    }

    // ---- Internals ----

    private String callWithRetries(String prompt, ImageData imageData,
                                   double temperature, int maxTokens, boolean jsonResponse) {
        if (!properties.isConfigured()) {
            throw new AiServiceException(
                    "No LLM API key configured. Set LLM_API_KEY (or OPENROUTER_API_KEY) before starting the backend.");
        }

        int cappedTokens = Math.min(maxTokens, properties.maxOutputTokens());
        Map<String, Object> body = buildRequestBody(prompt, imageData, temperature, cappedTokens, jsonResponse);
        RestClientException lastError = null;

        for (int attempt = 1; attempt <= properties.maxRetries(); attempt++) {
            try {
                long start = System.currentTimeMillis();
                JsonNode response = restClient.post()
                        .uri("/chat/completions")
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);

                String content = response != null
                        ? response.path("choices").path(0).path("message").path("content").asText("")
                        : "";

                log.info("LLM call completed: model={}, durationMs={}, attempt={}, tokens={}",
                        properties.model(), System.currentTimeMillis() - start, attempt,
                        response != null ? response.path("usage").path("total_tokens").asText("?") : "?");

                return stripCodeFences(content.trim());
            } catch (RestClientException e) {
                lastError = e;
                if (isNonRetryable(e)) {
                    // 402 (out of credits), 401/403 (bad key), 400 — retrying cannot help
                    throw new AiServiceException(e.getMessage());
                }
                log.warn("LLM call failed (attempt {}/{}): {}", attempt, properties.maxRetries(), e.getMessage());
                if (attempt < properties.maxRetries()) {
                    sleep(1000L * attempt);
                }
            }
        }

        throw new AiServiceException(
                "AI request failed after %d attempts: %s".formatted(
                        properties.maxRetries(),
                        lastError != null ? lastError.getMessage() : "Unknown error"));
    }

    private Map<String, Object> buildRequestBody(String prompt, ImageData imageData,
                                                 double temperature, int maxTokens, boolean jsonResponse) {
        Object content;
        if (imageData != null) {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("type", "text", "text", prompt));
            parts.add(Map.of("type", "image_url", "image_url", Map.of(
                    "url", "data:%s;base64,%s".formatted(imageData.mimeType(), imageData.base64()))));
            content = parts;
        } else {
            content = prompt;
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        if (jsonResponse) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    /** Client errors (except 429 rate limits) will fail identically on every retry. */
    private static boolean isNonRetryable(RestClientException e) {
        if (e instanceof org.springframework.web.client.RestClientResponseException response) {
            int status = response.getStatusCode().value();
            return status >= 400 && status < 500 && status != 429;
        }
        return false;
    }

    private static String stripCodeFences(String text) {
        return text
                .replaceFirst("(?i)^```(?:json)?\\s*\\n?", "")
                .replaceFirst("\\n?```\\s*$", "")
                .trim();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
