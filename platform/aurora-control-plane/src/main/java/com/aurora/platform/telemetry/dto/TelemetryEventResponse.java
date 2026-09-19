package com.aurora.platform.telemetry.dto;

import com.aurora.platform.telemetry.entity.TelemetryType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TelemetryEventResponse(
        UUID id,
        UUID resourceId,
        Instant timestamp,
        TelemetryType type,
        String metricName,
        Double value,
        String unit,
        Map<String, Object> metadata
) {}
