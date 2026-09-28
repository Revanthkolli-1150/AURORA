package com.aurora.platform.intelligence.historical.dto;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.resource.entity.ResourceType;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response DTO representing a historical incident match with explainable scoring.
 */
public record SimilarIncidentResponse(
        UUID historicalIncidentId,
        UUID resourceId,
        String resourceName,
        ResourceType resourceType,
        IncidentSeverity severity,
        Double similarityScore,
        SimilarityBreakdownResponse breakdown,
        String primaryRcaCause,
        Instant resolvedAt,
        Long resolutionDurationSeconds,
        String explanation
) {}
