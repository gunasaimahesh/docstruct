package com.docstruct.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.docstruct.llm.LlmClient;
import com.docstruct.repository.DynamicTableRepository;

/** Reports API liveness plus the status of its two dependencies: database and LLM. */
@RestController
public class HealthController {

    private final DynamicTableRepository dynamicTableRepository;
    private final LlmClient llmClient;
    private final Instant startTime = Instant.now();

    public HealthController(DynamicTableRepository dynamicTableRepository, LlmClient llmClient) {
        this.dynamicTableRepository = dynamicTableRepository;
        this.llmClient = llmClient;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> checks = new LinkedHashMap<>();
        boolean healthy = true;

        // Database connectivity
        try {
            long start = System.currentTimeMillis();
            dynamicTableRepository.ping();
            checks.put("database", Map.of(
                    "status", "healthy",
                    "latencyMs", System.currentTimeMillis() - start));
        } catch (Exception e) {
            healthy = false;
            checks.put("database", Map.of(
                    "status", "unhealthy",
                    "error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }

        // LLM configuration (no API call — just config presence)
        LlmClient.LlmStatus llm = llmClient.getStatus();
        if (llm.configured()) {
            checks.put("llm", Map.of(
                    "status", "healthy",
                    "provider", llm.provider(),
                    "model", llm.model()));
        } else {
            healthy = false;
            checks.put("llm", Map.of(
                    "status", "unhealthy",
                    "error", "No LLM API key configured (set LLM_API_KEY or OPENROUTER_API_KEY)"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", healthy ? "healthy" : "degraded");
        body.put("timestamp", Instant.now().toString());
        body.put("uptimeSeconds", Instant.now().getEpochSecond() - startTime.getEpochSecond());
        body.put("dependencies", checks);

        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
