package com.aurora.platform.intelligence.narrative.controller;

import com.aurora.platform.intelligence.narrative.dto.IncidentNarrativeResponse;
import com.aurora.platform.intelligence.narrative.service.IncidentNarrativeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller exposing endpoints for Phase 3 LLM Operator Summaries & Runbook Synthesis.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentNarrativeController {

    private final IncidentNarrativeService incidentNarrativeService;

    public IncidentNarrativeController(IncidentNarrativeService incidentNarrativeService) {
        this.incidentNarrativeService = incidentNarrativeService;
    }

    /**
     * Generates or retrieves the narrative summary for the latest RCA analysis of the incident.
     * Canonical ADR-007 idempotent contract: returns existing persisted narrative if already present.
     *
     * @param incidentId Target incident ID
     * @return Generated or cached narrative response
     */
    @PostMapping("/{incidentId}/narrative")
    public ResponseEntity<IncidentNarrativeResponse> generateOrGetNarrative(
            @PathVariable UUID incidentId
    ) {
        IncidentNarrativeResponse response = incidentNarrativeService.generateOrGetNarrative(incidentId);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the latest persisted narrative summary for an incident without triggering generation.
     *
     * @param incidentId Target incident ID
     * @return Latest persisted narrative response
     */
    @GetMapping("/{incidentId}/narrative")
    public ResponseEntity<IncidentNarrativeResponse> getNarrative(@PathVariable UUID incidentId) {
        IncidentNarrativeResponse response = incidentNarrativeService.getNarrative(incidentId);
        return ResponseEntity.ok(response);
    }
}
