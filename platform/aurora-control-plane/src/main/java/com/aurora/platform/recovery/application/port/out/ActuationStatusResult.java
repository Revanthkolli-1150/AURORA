package com.aurora.platform.recovery.application.port.out;

public record ActuationStatusResult(
        ActuationStatus status,
        String message
) {}
