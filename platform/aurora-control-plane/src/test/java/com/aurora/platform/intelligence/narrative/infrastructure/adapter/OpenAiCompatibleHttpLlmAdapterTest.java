package com.aurora.platform.intelligence.narrative.infrastructure.adapter;

import com.aurora.platform.intelligence.narrative.application.port.out.LlmGenerationResult;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmPromptRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleHttpLlmAdapterTest {

    private NarrativeProperties properties;
    private ObjectMapper objectMapper;
    private HttpClient httpClient;
    private NarrativeCircuitBreaker circuitBreaker;
    private OpenAiCompatibleHttpLlmAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new NarrativeProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://api.openai.com/v1");
        properties.setApiKey("test-api-key");
        properties.setModel("gpt-4o-mini");
        properties.setTimeoutMs(5000);
        properties.setMaxRetries(1);
        properties.setTemperature(0.0);
        properties.setFailureThreshold(3);
        properties.setOpenDurationSeconds(60);

        objectMapper = new ObjectMapper();
        httpClient = mock(HttpClient.class);
        circuitBreaker = new NarrativeCircuitBreaker(3, 60);

        adapter = new OpenAiCompatibleHttpLlmAdapter(properties, objectMapper, httpClient, circuitBreaker);
    }

    @Test
    @DisplayName("Successful 200 response returns parsed content and tokens")
    void testSuccessfulGeneration() throws Exception {
        String jsonResponse = """
                {
                  "id": "chatcmpl-123",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\\"headline\\":\\"Test Summary\\"}"
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 150,
                    "completion_tokens": 50
                  }
                }
                """;

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(jsonResponse);

        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(mockResponse);

        LlmPromptRequest request = new LlmPromptRequest(
                "System instruction",
                "User prompt",
                "JSON",
                0.0,
                5000,
                Map.of()
        );

        LlmGenerationResult result = adapter.generate(request);

        assertThat(result.successful()).isTrue();
        assertThat(result.structuredJson()).isEqualTo("{\"headline\":\"Test Summary\"}");
        assertThat(result.provider()).isEqualTo("openai-compatible");
        assertThat(result.modelName()).isEqualTo("gpt-4o-mini");
        assertThat(result.promptTokens()).isEqualTo(150);
        assertThat(result.completionTokens()).isEqualTo(50);
        assertThat(circuitBreaker.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Non-2xx status code returns failure and increments circuit breaker failure count")
    void testNon2xxStatusCode() throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("{\"error\": \"Rate limit exceeded\"}");

        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(mockResponse);

        LlmPromptRequest request = new LlmPromptRequest("Sys", "User", "JSON", 0.0, 5000, Map.of());

        LlmGenerationResult result = adapter.generate(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("status code 429");
        assertThat(circuitBreaker.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("HttpTimeoutException triggers retries and records failure")
    void testTimeoutHandling() throws Exception {
        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new HttpTimeoutException("request timed out"));

        LlmPromptRequest request = new LlmPromptRequest("Sys", "User", "JSON", 0.0, 5000, Map.of());

        LlmGenerationResult result = adapter.generate(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("timeout");
        // Initial attempt + 1 retry = 2 calls
        verify(httpClient, times(2)).send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertThat(circuitBreaker.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("Malformed JSON response without choices array returns failure")
    void testMalformedResponseMissingChoices() throws Exception {
        String malformedJson = "{\"id\": \"chatcmpl-123\", \"choices\": []}";

        @SuppressWarnings("unchecked")
        HttpResponse<String> mockResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(malformedJson);

        when(httpClient.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(mockResponse);

        LlmPromptRequest request = new LlmPromptRequest("Sys", "User", "JSON", 0.0, 5000, Map.of());

        LlmGenerationResult result = adapter.generate(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("Malformed response: choices array is missing or empty");
    }

    @Test
    @DisplayName("Disabled configuration bypasses HTTP execution and returns failure")
    void testDisabledConfiguration() {
        properties.setEnabled(false);

        LlmPromptRequest request = new LlmPromptRequest("Sys", "User", "JSON", 0.0, 5000, Map.of());

        LlmGenerationResult result = adapter.generate(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("disabled by configuration");
    }

    @Test
    @DisplayName("Circuit breaker in OPEN state immediately rejects execution without HTTP call")
    void testCircuitBreakerOpenRejection() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertThat(circuitBreaker.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);

        LlmPromptRequest request = new LlmPromptRequest("Sys", "User", "JSON", 0.0, 5000, Map.of());

        LlmGenerationResult result = adapter.generate(request);

        assertThat(result.successful()).isFalse();
        assertThat(result.errorMessage()).contains("Circuit breaker is OPEN");
    }
}
