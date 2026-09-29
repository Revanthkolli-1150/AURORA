package com.aurora.platform.recovery.application.gate;

import com.aurora.platform.recovery.entity.RecoveryActionEntity;

public record GateEvaluationResult(
        boolean allowed,
        String rejectionReason,
        RecoveryActionEntity action
) {
    public static GateEvaluationResult permit(RecoveryActionEntity action) {
        return new GateEvaluationResult(true, null, action);
    }

    public static GateEvaluationResult deny(String reason, RecoveryActionEntity action) {
        return new GateEvaluationResult(false, reason, action);
    }
}
