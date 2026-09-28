package com.aurora.platform.incident.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO representing an anomaly observation that serves as evidence for an incident.
 */
public record IncidentAnomalyEvidenceResponse(
        UUID id,
        UUID incidentId,
        UUID resourceId,
        String metricName,
        Double observedValue,
        Double anomalyScore,
        Double zScore,
        String detectionMethod,
        Instant observedAt,
        Instant createdAt
) {}
