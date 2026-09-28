package com.aurora.platform.telemetry.dto;

import com.aurora.platform.telemetry.entity.TelemetryType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record IngestTelemetryRequest(
        @NotNull(message = "Resource ID is required")
        UUID resourceId,

        @NotNull(message = "Timestamp is required")
        Instant timestamp,

        @NotNull(message = "Telemetry type is required")
        TelemetryType type,

        @NotBlank(message = "Metric name is required")
        @Size(min = 1, max = 255, message = "Metric name must be between 1 and 255 characters")
        String metricName,

        @NotNull(message = "Value is required")
        Double value,

        @NotBlank(message = "Unit is required")
        @Size(min = 1, max = 50, message = "Unit must be between 1 and 50 characters")
        String unit,

        Map<String, Object> metadata
) {
    public IngestTelemetryRequest(UUID resourceId, Instant timestamp, TelemetryType type, String metricName, Double value, String unit) {
        this(resourceId, timestamp, type, metricName, value, unit, null);
    }
}
