package com.aurora.platform.intelligence.narrative.application.port.out;

/**
 * Provider-agnostic result returned from the outbound LLM port.
 */
public record LlmGenerationResult(
        String rawText,
        String structuredJson,
        String provider,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Long durationMs,
        boolean successful,
        String errorMessage
) {
    public static LlmGenerationResult success(
            String rawText,
            String structuredJson,
            String provider,
            String modelName,
            Integer promptTokens,
            Integer completionTokens,
            Long durationMs
    ) {
        return new LlmGenerationResult(
                rawText,
                structuredJson,
                provider,
                modelName,
                promptTokens,
                completionTokens,
                durationMs,
                true,
                null
        );
    }

    public static LlmGenerationResult failure(
            String provider,
            String modelName,
            Long durationMs,
            String errorMessage
    ) {
        return new LlmGenerationResult(
                null,
                null,
                provider,
                modelName,
                0,
                0,
                durationMs,
                false,
                errorMessage
        );
    }
}
