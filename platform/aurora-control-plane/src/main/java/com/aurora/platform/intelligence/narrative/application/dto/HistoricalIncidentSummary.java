package com.aurora.platform.intelligence.narrative.application.dto;

import java.util.UUID;

/**
 * Summarized deterministic historical similarity match for prompt assembly.
 */
public record HistoricalIncidentSummary(
        UUID historicalIncidentId,
        String resourceName,
        Double similarityScore,
        String primaryRcaCause,
        Long resolutionDurationSeconds
) {
}
