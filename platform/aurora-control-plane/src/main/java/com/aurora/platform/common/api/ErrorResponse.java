package com.aurora.platform.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        Map<String, String> details
) {
    public static ErrorResponse of(int status, String error, String message, String path, String traceId) {
        return new ErrorResponse(Instant.now(), status, error, message, path, traceId, null);
    }

    public static ErrorResponse of(int status, String error, String message, String path, String traceId, Map<String, String> details) {
        return new ErrorResponse(Instant.now(), status, error, message, path, traceId, details);
    }
}
