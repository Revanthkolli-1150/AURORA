package com.aurora.platform.recovery.application.port.out;

public record ActuationResult(
        ActuationStatus status,
        String externalExecutionReference,
        String message,
        String rawPayload
) {
    public static ActuationResult success(String externalRef, String message, String rawPayload) {
        return new ActuationResult(ActuationStatus.SUCCESS, externalRef, message, rawPayload);
    }

    public static ActuationResult failure(String externalRef, String message, String rawPayload) {
        return new ActuationResult(ActuationStatus.FAILURE, externalRef, message, rawPayload);
    }

    public static ActuationResult timeout(String externalRef, String message) {
        return new ActuationResult(ActuationStatus.TIMEOUT, externalRef, message, null);
    }
}
