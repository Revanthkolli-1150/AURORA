package com.aurora.platform.intelligence.narrative.infrastructure.adapter;

import com.aurora.platform.intelligence.narrative.application.port.out.LlmClientPort;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmGenerationResult;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmPromptRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Standard HTTP adapter communicating with any OpenAI-compatible Chat Completions endpoint
 * (e.g. OpenAI, Azure OpenAI, Google Vertex AI, AWS Bedrock proxy, local vLLM / Ollama).
 *
 * <p>Operational Guarantees:
 * <ul>
 *   <li>Enforces a hard 5000ms timeout ceiling per ADR-007.</li>
 *   <li>Protected by an in-process circuit breaker (trips on 3 consecutive failures, 60s cooldown).</li>
 *   <li>Zero external vendor SDK dependencies; implemented via pure Java 21 {@link HttpClient}.</li>
 * </ul>
 */
@Component
public class OpenAiCompatibleHttpLlmAdapter implements LlmClientPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleHttpLlmAdapter.class);
    private static final int HARD_TIMEOUT_CEILING_MS = 5000;

    private final NarrativeProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final NarrativeCircuitBreaker circuitBreaker;

    @Autowired
    public OpenAiCompatibleHttpLlmAdapter(NarrativeProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                        .build(),
                new NarrativeCircuitBreaker(properties.getFailureThreshold(), properties.getOpenDurationSeconds())
        );
    }

    public OpenAiCompatibleHttpLlmAdapter(
            NarrativeProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            NarrativeCircuitBreaker circuitBreaker
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public LlmGenerationResult generate(LlmPromptRequest request) {
        if (!properties.isEnabled()) {
            log.info("Narrative LLM generation is disabled by configuration");
            return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), 0L, "LLM generation disabled by configuration");
        }

        if (!circuitBreaker.allowExecution()) {
            log.warn("Narrative generation rejected: Circuit breaker is OPEN");
            return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), 0L, "Circuit breaker is OPEN");
        }

        int effectiveTimeoutMs = Math.min(
                request.timeoutMs() != null ? request.timeoutMs() : properties.getTimeoutMs(),
                HARD_TIMEOUT_CEILING_MS
        );

        int maxAttempts = 1 + Math.max(0, properties.getMaxRetries());
        long startTime = System.currentTimeMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LlmGenerationResult result = executeHttpCall(request, effectiveTimeoutMs, startTime);
                if (result.successful()) {
                    circuitBreaker.recordSuccess();
                    return result;
                }

                if (attempt == maxAttempts) {
                    circuitBreaker.recordFailure();
                    return result;
                }
            } catch (HttpTimeoutException e) {
                log.warn("LLM generation attempt {} timed out after {}ms: {}", attempt, effectiveTimeoutMs, e.getMessage());
                if (attempt == maxAttempts) {
                    circuitBreaker.recordFailure();
                    long duration = System.currentTimeMillis() - startTime;
                    return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), duration, "LLM provider timeout: " + e.getMessage());
                }
            } catch (Exception e) {
                log.warn("LLM generation attempt {} failed with error: {}", attempt, e.getMessage());
                if (attempt == maxAttempts) {
                    circuitBreaker.recordFailure();
                    long duration = System.currentTimeMillis() - startTime;
                    return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), duration, "LLM invocation error: " + e.getMessage());
                }
            }
        }

        circuitBreaker.recordFailure();
        long duration = System.currentTimeMillis() - startTime;
        return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), duration, "Exhausted retries");
    }

    private LlmGenerationResult executeHttpCall(LlmPromptRequest request, int timeoutMs, long startTime) throws Exception {
        String endpointUrl = buildEndpointUrl(properties.getBaseUrl());

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", properties.getModel());
        payload.put("temperature", request.temperature() != null ? request.temperature() : properties.getTemperature());

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemInstruction() != null && !request.systemInstruction().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemInstruction()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        payload.put("messages", messages);

        if (request.expectedJsonSchema() != null && !request.expectedJsonSchema().isBlank()) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        String requestBody = objectMapper.writeValueAsString(payload);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpointUrl))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + properties.getApiKey().trim());
        }

        HttpRequest httpRequest = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        long duration = System.currentTimeMillis() - startTime;

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            String responseBody = response.body();
            JsonNode root = objectMapper.readTree(responseBody);

            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), duration, "Malformed response: choices array is missing or empty");
            }

            JsonNode message = choices.get(0).path("message");
            String content = message.path("content").asText(null);
            if (content == null || content.isBlank()) {
                return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), duration, "Empty content returned in LLM choices message");
            }

            JsonNode usage = root.path("usage");
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);

            return LlmGenerationResult.success(
                    content,
                    content,
                    properties.getProvider(),
                    properties.getModel(),
                    promptTokens,
                    completionTokens,
                    duration
            );
        } else {
            String sanitizedBody = response.body() != null
                    ? com.aurora.platform.intelligence.narrative.domain.DataSanitizer.sanitizeText(truncate(response.body(), 200))
                    : "";
            String errorMsg = String.format("LLM provider returned non-2xx status code %d: %s", response.statusCode(), sanitizedBody);
            log.warn(errorMsg);
            return LlmGenerationResult.failure(properties.getProvider(), properties.getModel(), duration, errorMsg);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String buildEndpointUrl(String baseUrl) {
        String cleanBase = baseUrl.trim();
        if (cleanBase.endsWith("/")) {
            cleanBase = cleanBase.substring(0, cleanBase.length() - 1);
        }
        if (cleanBase.endsWith("/chat/completions")) {
            return cleanBase;
        }
        return cleanBase + "/chat/completions";
    }

    public NarrativeCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }
}
