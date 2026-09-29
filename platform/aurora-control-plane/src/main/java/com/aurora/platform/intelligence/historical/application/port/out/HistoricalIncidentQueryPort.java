package com.aurora.platform.intelligence.historical.application.port.out;

import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound application query port for accessing historical incident data,
 * canonical signatures, and the candidate corpus.
 */
public interface HistoricalIncidentQueryPort {

    /**
     * Resolves the canonical signature for a specific incident by ID.
     *
     * @param incidentId Unique incident identifier
     * @return Optional containing the resolved signature, or empty if incident does not exist
     */
    Optional<IncidentSignature> findSignature(UUID incidentId);

    /**
     * Retrieves the candidate historical corpus consisting of the most recent
     * RESOLVED incidents (excluding the target incident), ordered by resolvedAt DESC, incidentId ASC.
     *
     * @param excludedIncidentId Target incident ID to exclude from the historical corpus
     * @param maxCandidates      Maximum number of historical candidates to evaluate (e.g., 200)
     * @return List of historical candidate incident signatures
     */
    List<IncidentSignature> findCandidateCorpusSignatures(UUID excludedIncidentId, int maxCandidates);
}
