package com.aurora.platform.intelligence.rca.controller;

import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/rca")
public class RcaController {

    private final RcaAnalysisService rcaAnalysisService;

    public RcaController(RcaAnalysisService rcaAnalysisService) {
        this.rcaAnalysisService = rcaAnalysisService;
    }

    @PostMapping
    public ResponseEntity<RcaAnalysisResponse> analyzeIncident(@PathVariable UUID incidentId) {
        RcaAnalysisResponse response = rcaAnalysisService.analyzeIncident(incidentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<RcaAnalysisResponse> getLatestRcaAnalysis(@PathVariable UUID incidentId) {
        RcaAnalysisResponse response = rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{analysisId}")
    public ResponseEntity<RcaAnalysisResponse> getRcaAnalysisById(
            @PathVariable UUID incidentId,
            @PathVariable UUID analysisId) {
        RcaAnalysisResponse response = rcaAnalysisService.getAnalysisById(incidentId, analysisId);
        return ResponseEntity.ok(response);
    }
}
