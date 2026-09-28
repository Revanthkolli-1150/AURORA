package com.aurora.platform.intelligence.historical.controller;

import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.intelligence.historical.service.HistoricalIncidentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing endpoints for Phase 2C Historical Incident Intelligence.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class HistoricalIncidentController {

    private final HistoricalIncidentService historicalIncidentService;

    public HistoricalIncidentController(HistoricalIncidentService historicalIncidentService) {
        this.historicalIncidentService = historicalIncidentService;
    }

    /**
     * Retrieves historically similar resolved incidents for a target incident.
     *
     * @param incidentId Target incident ID
     * @param limit      Optional result limit (default: 5, max: 20)
     * @param minScore   Optional minimum similarity threshold (default: 0.30, range: [0.0, 1.0])
     * @return List of matching historical incidents ranked deterministically
     */
    @GetMapping("/{incidentId}/similar")
    public ResponseEntity<List<SimilarIncidentResponse>> getSimilarIncidents(
            @PathVariable UUID incidentId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Double minScore
    ) {
        List<SimilarIncidentResponse> matches =
                historicalIncidentService.findSimilarIncidents(incidentId, limit, minScore);
        return ResponseEntity.ok(matches);
    }
}
