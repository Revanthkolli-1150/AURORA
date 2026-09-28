package com.aurora.platform.intelligence.historical.domain.model;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.resource.entity.ResourceType;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable signature capturing the structural, metric, topological, and diagnostic
 * attributes of an incident for deterministic historical pattern matching.
 */
public record IncidentSignature(
        UUID incidentId,
        UUID resourceId,
        String resourceName,
        ResourceType resourceType,
        IncidentSeverity severity,
        Set<String> anomalousMetrics,
        Set<String> topologyTokens,
        String primaryRcaCause,
        Instant detectedAt,
        Instant resolvedAt
) {
    public IncidentSignature {
        Objects.requireNonNull(incidentId, "incidentId must not be null");
        Objects.requireNonNull(resourceId, "resourceId must not be null");
        Objects.requireNonNull(resourceType, "resourceType must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        anomalousMetrics = (anomalousMetrics != null)
                ? Collections.unmodifiableSet(anomalousMetrics)
                : Collections.emptySet();
        topologyTokens = (topologyTokens != null)
                ? Collections.unmodifiableSet(topologyTokens)
                : Collections.emptySet();
    }
}
