package com.aurora.platform.recovery.controller;

import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.service.RecoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/incidents")
public class RecoveryController {

    private final RecoveryService recoveryService;

    public RecoveryController(RecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @GetMapping("/{incidentId}/recovery-plan")
    public ResponseEntity<RecoveryPlanResponse> getRecoveryPlan(@PathVariable UUID incidentId) {
        RecoveryPlanResponse plan = recoveryService.getRecoveryPlanByIncidentId(incidentId);
        return ResponseEntity.ok(plan);
    }
}
