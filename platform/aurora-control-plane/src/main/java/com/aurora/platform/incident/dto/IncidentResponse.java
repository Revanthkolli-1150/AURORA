package com.aurora.platform.incident.dto;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID resourceId,
        String title,
        String description,
        IncidentSeverity severity,
        IncidentStatus status,
        Double confidence,
        String rootCause,
        Instant detectedAt,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {}
