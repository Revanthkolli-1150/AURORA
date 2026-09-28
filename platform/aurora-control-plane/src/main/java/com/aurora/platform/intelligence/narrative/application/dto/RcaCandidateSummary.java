package com.aurora.platform.intelligence.narrative.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * Summarized deterministic RCA candidate context for prompt assembly.
 */
public record RcaCandidateSummary(
        UUID candidateResourceId,
        String candidateResourceName,
        String candidateMetric,
        String candidateCause,
        Double evidenceScore,
        Integer rank,
        String explanation,
        Boolean primaryCandidate,
        List<String> evidenceItemSummaries
) {
}
