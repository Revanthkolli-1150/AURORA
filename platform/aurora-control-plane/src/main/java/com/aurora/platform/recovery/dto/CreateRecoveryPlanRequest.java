package com.aurora.platform.recovery.dto;

import com.aurora.platform.recovery.entity.RecoveryRisk;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record CreateRecoveryPlanRequest(
        @NotNull(message = "Incident ID is required")
        UUID incidentId,

        @NotBlank(message = "Reasoning is required")
        String reasoning,

        @NotNull(message = "Confidence is required")
        Double confidence,

        @NotNull(message = "Risk is required")
        RecoveryRisk risk,

        Boolean approvalRequired,

        @Valid
        List<CreateRecoveryActionRequest> actions
) {}
