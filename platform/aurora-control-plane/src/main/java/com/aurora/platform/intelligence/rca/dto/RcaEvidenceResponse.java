package com.aurora.platform.intelligence.rca.dto;

import com.aurora.platform.intelligence.rca.entity.RcaEvidenceType;

import java.time.Instant;
import java.util.UUID;

public record RcaEvidenceResponse(
        UUID id,
        UUID candidateId,
        RcaEvidenceType evidenceType,
        UUID resourceId,
        String metricName,
        Double observedValue,
        Double anomalyScore,
        Instant observedAt,
        Double contributionScore,
        String explanation,
        Instant createdAt
) {
}
