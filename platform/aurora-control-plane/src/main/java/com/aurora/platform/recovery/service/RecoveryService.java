package com.aurora.platform.recovery.service;

import com.aurora.platform.recovery.dto.CreateRecoveryPlanRequest;
import com.aurora.platform.recovery.dto.RecoveryActionResponse;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;

import java.util.List;
import java.util.UUID;

public interface RecoveryService {

    RecoveryPlanResponse getRecoveryPlanByIncidentId(UUID incidentId);

    RecoveryPlanResponse generateRecoveryPlan(UUID incidentId);

    RecoveryActionResponse approveRecoveryAction(UUID incidentId, UUID actionId);

    RecoveryPlanResponse createRecoveryPlan(CreateRecoveryPlanRequest request);

    /**
     * Backward-compatible overload for internal tests.
     */
    RecoveryPlanResponse createRecoveryPlan(UUID incidentId,
                                           String reasoning,
                                           Double confidence,
                                           RecoveryRisk risk,
                                           Boolean approvalRequired,
                                           List<RecoveryActionEntity> actions);
}
