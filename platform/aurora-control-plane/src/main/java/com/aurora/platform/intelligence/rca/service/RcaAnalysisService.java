package com.aurora.platform.intelligence.rca.service;

import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;

import java.util.UUID;

public interface RcaAnalysisService {

    /**
     * Executes a deterministic Root Cause Analysis (RCA) investigation on an existing incident.
     *
     * @param incidentId ID of the incident to analyze
     * @return Completed RCA analysis with ranked candidates and supporting evidence
     */
    RcaAnalysisResponse analyzeIncident(UUID incidentId);

    /**
     * Retrieves the latest RCA analysis conducted for an incident.
     *
     * @param incidentId ID of the incident
     * @return Latest RCA analysis response
     */
    RcaAnalysisResponse getLatestAnalysisByIncidentId(UUID incidentId);

    /**
     * Retrieves a specific RCA analysis by incident ID and analysis ID.
     *
     * @param incidentId ID of the incident
     * @param analysisId ID of the RCA analysis
     * @return Specific RCA analysis response
     */
    RcaAnalysisResponse getAnalysisById(UUID incidentId, UUID analysisId);
}
