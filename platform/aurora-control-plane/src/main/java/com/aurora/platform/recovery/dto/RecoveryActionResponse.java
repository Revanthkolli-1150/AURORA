package com.aurora.platform.recovery.dto;

import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.resource.entity.ResourceType;

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
        Instant completedAt,
        UUID targetResourceId,
        String targetEnvironment,
        ResourceType targetResourceType,
        String targetResourceName,
        String approvedByUserId,
        String approvedByEmail,
        String approvedByCapability,
        Instant approvedAt,
        String approvalReason
) {
    // Backwards-compatible 8-argument constructor
    public RecoveryActionResponse(
            UUID id,
            UUID recoveryPlanId,
            String actionType,
            String target,
            RecoveryActionStatus status,
            String result,
            Instant startedAt,
            Instant completedAt
    ) {
        this(
                id,
                recoveryPlanId,
                actionType,
                target,
                status,
                result,
                startedAt,
                completedAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
