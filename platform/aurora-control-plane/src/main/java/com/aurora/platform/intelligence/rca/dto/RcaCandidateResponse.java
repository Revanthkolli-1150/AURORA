package com.aurora.platform.intelligence.rca.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RcaCandidateResponse(
        UUID id,
        UUID analysisId,
        UUID candidateResourceId,
        String candidateMetric,
        String candidateCause,
        Double evidenceScore,
        Integer rank,
        String explanation,
        Boolean primaryCandidate,
        Instant createdAt,
        List<RcaEvidenceResponse> evidence
) {
}
