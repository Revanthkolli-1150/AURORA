package com.aurora.platform.intelligence.narrative.infrastructure.adapter;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Phase 3 LLM Operator Narrative generation.
 */
@Configuration
@ConfigurationProperties(prefix = "aurora.intelligence.narrative")
@Getter
@Setter
public class NarrativeProperties {

    /**
     * Whether narrative generation is enabled. If false, deterministic fallback is always used.
     */
    private boolean enabled = true;

    /**
     * Provider identifier (e.g. "openai-compatible", "mock").
     */
    private String provider = "openai-compatible";

    /**
     * Base URL for the OpenAI-compatible REST API endpoint.
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * Authentication API key or bearer token.
     */
    private String apiKey = "";

    /**
     * Model identifier (e.g. "gpt-4o-mini", "llama-3-8b-instruct").
     */
    private String model = "gpt-4o-mini";

    /**
     * Hard timeout ceiling in milliseconds. Defaults to 5000ms per ADR-007.
     */
    private int timeoutMs = 5000;

    /**
     * Maximum retry attempts on transient network error. Defaults to 1 per ADR-007.
     */
    private int maxRetries = 1;

    /**
     * Sampling temperature. Defaults to 0.0 for deterministic grounding.
     */
    private double temperature = 0.0;

    /**
     * Number of consecutive failures before circuit breaker trips open. Defaults to 3.
     */
    private int failureThreshold = 3;

    /**
     * Duration in seconds the circuit breaker remains OPEN before half-open probe. Defaults to 60.
     */
    private int openDurationSeconds = 60;

    @Override
    public String toString() {
        return "NarrativeProperties{" +
                "enabled=" + enabled +
                ", provider='" + provider + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", apiKey='" + (apiKey == null || apiKey.isBlank() ? "<not-set>" : "[PROTECTED]") + '\'' +
                ", model='" + model + '\'' +
                ", timeoutMs=" + timeoutMs +
                ", maxRetries=" + maxRetries +
                ", temperature=" + temperature +
                ", failureThreshold=" + failureThreshold +
                ", openDurationSeconds=" + openDurationSeconds +
                '}';
    }
}
