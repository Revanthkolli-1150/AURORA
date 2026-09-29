package com.aurora.platform.intelligence.narrative.application.port.out;

import java.util.Map;

/**
 * Provider-agnostic request model sent across the outbound LLM port.
 */
public record LlmPromptRequest(
        String systemInstruction,
        String userPrompt,
        String expectedJsonSchema,
        Double temperature,
        Integer timeoutMs,
        Map<String, String> metadata
) {
}
