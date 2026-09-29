package com.aurora.platform.recovery.application.gate;

import com.aurora.platform.recovery.entity.RecoveryActionEntity;

public record GateEvaluationResult(
        boolean allowed,
        String rejectionReason,
        RecoveryActionEntity action,
        boolean isReplayOrTerminal
) {
    public GateEvaluationResult(boolean allowed, String rejectionReason, RecoveryActionEntity action) {
        this(allowed, rejectionReason, action, false);
    }

    public static GateEvaluationResult permit(RecoveryActionEntity action) {
        return new GateEvaluationResult(true, null, action, false);
    }

    public static GateEvaluationResult deny(String reason, RecoveryActionEntity action) {
        return new GateEvaluationResult(false, reason, action, false);
    }

    public static GateEvaluationResult replayDenial(String reason, RecoveryActionEntity action) {
        return new GateEvaluationResult(false, reason, action, true);
    }
}
