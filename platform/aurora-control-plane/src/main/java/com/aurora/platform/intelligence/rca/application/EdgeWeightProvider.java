package com.aurora.platform.intelligence.rca.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Application port for providing empirical dependency edge weights for RCA candidates.
 *
 * <p>Phase 2B deterministic RCA depends only on this interface and defaults to static
 * weights (0.20) if no implementation is present.
 */
public interface EdgeWeightProvider {

    /**
     * Computes the empirical dependency edge weight for directed relationship u -> v (u DEPENDS_ON v).
     *
     * @param investigatedResourceId   u, the downstream resource experiencing the incident
     * @param candidateResourceId      v, the upstream dependency candidate
     * @param targetIncidentDetectedAt timestamp of the current incident detection (temporal leakage guard)
     * @param targetIncidentId         ID of the current incident (self-exclusion guard)
     * @return the calculation result including clamped final weight, raw weight, counts, and explanation
     */
    EdgeWeightResult getEdgeWeight(
            UUID investigatedResourceId,
            UUID candidateResourceId,
            Instant targetIncidentDetectedAt,
            UUID targetIncidentId
    );
}
