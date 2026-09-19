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

        Instant timestamp,

        @NotNull(message = "Telemetry type is required")
        TelemetryType type,

        @NotBlank(message = "Metric name is required")
        @Size(max = 255, message = "Metric name must not exceed 255 characters")
        String metricName,

        @NotNull(message = "Value is required")
        Double value,

        @NotBlank(message = "Unit is required")
        @Size(max = 50, message = "Unit must not exceed 50 characters")
        String unit,

        Map<String, Object> metadata
) {}
