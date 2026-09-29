package com.aurora.platform.intelligence.narrative.application.port.out;

/**
 * Hexagonal outbound application port for executing LLM prompt generation requests.
 * Completely provider-neutral (no vendor SDKs or specific runtime concepts).
 */
public interface LlmClientPort {

    /**
     * Executes prompt generation against the configured LLM provider or local engine.
     *
     * @param request Validated prompt payload with system instructions and JSON schema
     * @return Execution result with generated text and operational metadata
     */
    LlmGenerationResult generate(LlmPromptRequest request);
}
