package com.aurora.platform.incident.controller;

import com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentService incidentService;

    public IncidentController(IncidentService incidentService) {
        this.incidentService = incidentService;
    }

    @GetMapping
    public ResponseEntity<List<IncidentResponse>> getAllIncidents(
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) IncidentSeverity severity) {
        List<IncidentResponse> incidents = incidentService.getAllIncidents(resourceId, status, severity);
        return ResponseEntity.ok(incidents);
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentResponse> getIncidentById(@PathVariable UUID id) {
        IncidentResponse incident = incidentService.getIncidentById(id);
        return ResponseEntity.ok(incident);
    }

    @GetMapping("/{id}/evidence")
    public ResponseEntity<List<IncidentAnomalyEvidenceResponse>> getIncidentEvidence(@PathVariable UUID id) {
        List<IncidentAnomalyEvidenceResponse> evidence = incidentService.getEvidenceByIncidentId(id);
        return ResponseEntity.ok(evidence);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{id}/status")
    public ResponseEntity<IncidentResponse> updateIncidentStatus(
            @PathVariable UUID id,
            @RequestParam IncidentStatus status) {
        IncidentResponse updated = incidentService.updateIncidentStatus(id, status);
        return ResponseEntity.ok(updated);
    }
}
