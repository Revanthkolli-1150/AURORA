package com.aurora.platform.recovery.dto;

import com.aurora.platform.recovery.entity.RecoveryRisk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecoveryPlanResponse(
        UUID id,
        UUID incidentId,
        String reasoning,
        Double confidence,
        RecoveryRisk risk,
        Boolean approvalRequired,
        List<RecoveryActionResponse> actions,
        Instant createdAt
) {}
