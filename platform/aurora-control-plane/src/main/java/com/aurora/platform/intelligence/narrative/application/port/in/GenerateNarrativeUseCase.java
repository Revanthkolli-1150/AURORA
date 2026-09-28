package com.aurora.platform.intelligence.narrative.application.port.in;

import com.aurora.platform.intelligence.narrative.dto.IncidentNarrativeResponse;

import java.util.UUID;

/**
 * Inbound application port defining the use case for incident narrative generation and retrieval.
 */
public interface GenerateNarrativeUseCase {

    /**
     * Generates or retrieves the narrative associated with the latest RCA analysis of the incident.
     * Strictly idempotent: if a narrative already exists for the latest analysis,
     * the cached narrative is returned immediately without reinvoking the LLM.
     *
     * @param incidentId Target incident ID
     * @return Completed incident narrative response
     */
    IncidentNarrativeResponse generateOrGetNarrative(UUID incidentId);

    /**
     * Retrieves the latest persisted narrative for an incident without triggering generation.
     *
     * @param incidentId Target incident ID
     * @return Existing incident narrative response
     */
    IncidentNarrativeResponse getNarrative(UUID incidentId);
}
