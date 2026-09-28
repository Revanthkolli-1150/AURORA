package com.aurora.platform.intelligence.historical.application.port.in;

import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;

import java.util.List;
import java.util.UUID;

/**
 * Inbound application port defining the use case for finding historically similar resolved incidents.
 */
public interface FindSimilarIncidentsUseCase {

    /**
     * Deterministically finds previously RESOLVED incidents structurally similar to the given incident.
     *
     * @param incidentId Target incident ID
     * @param limit      Maximum results to return (default: 5, max: 20)
     * @param minScore   Minimum composite similarity threshold (default: 0.30, range: [0.0, 1.0])
     * @return List of matching historical incidents ranked deterministically
     */
    List<SimilarIncidentResponse> findSimilarIncidents(UUID incidentId, Integer limit, Double minScore);
}
