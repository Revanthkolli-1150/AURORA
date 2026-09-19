package com.aurora.platform.recovery.dto;

import com.aurora.platform.recovery.entity.RecoveryActionStatus;

import java.time.Instant;
import java.util.UUID;

public record RecoveryActionResponse(
        UUID id,
        UUID recoveryPlanId,
        String actionType,
        String target,
        RecoveryActionStatus status,
        String result,
        Instant startedAt,
        Instant completedAt
) {}
