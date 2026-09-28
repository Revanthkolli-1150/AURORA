package com.aurora.platform.intelligence.rca.dto;

import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RcaAnalysisResponse(
        UUID id,
        UUID incidentId,
        RcaAnalysisStatus status,
        UUID investigatedResourceId,
        String summary,
        Double confidence,
        String confidenceLevel,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        List<RcaCandidateResponse> candidates
) {
}
