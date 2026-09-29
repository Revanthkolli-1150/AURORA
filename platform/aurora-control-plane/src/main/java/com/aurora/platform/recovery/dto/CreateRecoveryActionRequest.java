package com.aurora.platform.recovery.dto;

import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import jakarta.validation.constraints.NotBlank;

public record CreateRecoveryActionRequest(
        @NotBlank(message = "Action type is required")
        String actionType,

        @NotBlank(message = "Target is required")
        String target,

        RecoveryActionStatus status,

        String result
) {}
