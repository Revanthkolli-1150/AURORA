package com.aurora.platform.intelligence.narrative.repository;

import com.aurora.platform.intelligence.narrative.domain.IncidentNarrativeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for persisted incident narratives.
 */
@Repository
public interface IncidentNarrativeRepository extends JpaRepository<IncidentNarrativeEntity, UUID> {

    /**
     * Finds the narrative associated with a specific RCA analysis execution.
     */
    Optional<IncidentNarrativeEntity> findByRcaAnalysisId(UUID rcaAnalysisId);

    /**
     * Finds the latest persisted narrative for a given incident.
     */
    Optional<IncidentNarrativeEntity> findTopByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    /**
     * Checks if a narrative already exists for a given RCA analysis.
     */
    boolean existsByRcaAnalysisId(UUID rcaAnalysisId);

    /**
     * Deletes existing narrative for an RCA analysis (used during forced regeneration).
     */
    void deleteByRcaAnalysisId(UUID rcaAnalysisId);
}
