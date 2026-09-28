package com.aurora.platform.intelligence.narrative.application.dto;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.resource.entity.ResourceType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable aggregate of factual, deterministic Phase 1 & 2 evidence assembled
 * for prompt injection into the narrative generator.
 */
public record IncidentNarrativeContext(
        UUID incidentId,
        String incidentTitle,
        String incidentDescription,
        IncidentSeverity severity,
        IncidentStatus status,
        Instant detectedAt,
        UUID investigatedResourceId,
        String investigatedResourceName,
        ResourceType investigatedResourceType,
        String environment,
        UUID rcaAnalysisId,
        Double rcaConfidence,
        String rcaConfidenceLevel,
        String deterministicRcaSummary,
        RcaCandidateSummary primaryCandidate,
        List<RcaCandidateSummary> topSecondaryCandidates,
        List<HistoricalIncidentSummary> similarHistoricalIncidents,
        List<String> upstreamDependencies,
        List<String> downstreamDependents,
        Instant generatedAt
) {
}
