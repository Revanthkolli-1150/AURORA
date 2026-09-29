package com.aurora.platform.recovery.application.gate;

import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.ExecutionAttemptStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.repository.ExecutionAttemptRepository;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.service.RecoveryServiceImpl;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Centralized Execution Authorization Gate enforcing safety invariants S1–S17.
 * <p>
 * No actuator call may occur without passing through this gate.
 */
@Component
public class ExecutionAuthorizationGate {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAuthorizationGate.class);

    private final RecoveryActionRepository recoveryActionRepository;
    private final ExecutionAttemptRepository executionAttemptRepository;
    private final ResourceService resourceService;
    private final RecoveryServiceImpl recoveryService;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public ExecutionAuthorizationGate(
            RecoveryActionRepository recoveryActionRepository,
            ExecutionAttemptRepository executionAttemptRepository,
            ResourceService resourceService,
            RecoveryServiceImpl recoveryService) {
        this.recoveryActionRepository = recoveryActionRepository;
        this.executionAttemptRepository = executionAttemptRepository;
        this.resourceService = resourceService;
        this.recoveryService = recoveryService;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Evaluates all pre-dispatch safety invariants for a proposed execution attempt.
     */
    public GateEvaluationResult evaluate(UUID actionId, String idempotencyKey) {
        if (actionId == null) {
            return GateEvaluationResult.deny("Action ID is required", null);
        }

        Optional<RecoveryActionEntity> actionOpt = recoveryActionRepository.findById(actionId);
        if (actionOpt.isEmpty()) {
            return GateEvaluationResult.deny("Recovery action '" + actionId + "' not found", null);
        }
        RecoveryActionEntity action = actionOpt.get();

        // S3: Execution Prerequisite - action must be APPROVED or IN_PROGRESS
        if (action.getStatus() != RecoveryActionStatus.APPROVED && action.getStatus() != RecoveryActionStatus.IN_PROGRESS) {
            return GateEvaluationResult.deny("S3 Violation: Cannot execute action in status '" + action.getStatus() + "'", action);
        }

        // S1: Authentication Gate - approval must have authenticated operator record
        if (action.getApprovedByUserId() == null || action.getApprovedByUserId().isBlank()) {
            return GateEvaluationResult.deny("S1 Violation: Missing authenticated operator approval record", action);
        }

        // S7 & S17: Canonical Target Binding and Target Immutability
        if (action.getTargetResourceId() == null) {
            return GateEvaluationResult.deny("S7 Violation: Missing canonical target_resource_id", action);
        }
        if (resourceService != null) {
            try {
                ResourceResponse resource = resourceService.getResourceById(action.getTargetResourceId());
                if (resource == null) {
                    return GateEvaluationResult.deny("S7 Violation: Canonical target resource does not exist", action);
                }
                // Immutability verification: target environment cannot diverge
                if (!resource.environment().equalsIgnoreCase(action.getTargetEnvironment())) {
                    return GateEvaluationResult.deny("S17 Violation: Target environment diverged from canonical resource", action);
                }
            } catch (Exception ex) {
                return GateEvaluationResult.deny("S7 Violation: Unable to verify canonical target: " + ex.getMessage(), action);
            }
        }

        // S8: Production Human Authorization
        boolean isProduction = "production".equalsIgnoreCase(action.getTargetEnvironment());
        if (isProduction) {
            String cap = action.getApprovedByCapability();
            if (cap == null || (!cap.contains("RECOVERY_APPROVE_PRODUCTION") && !cap.contains("RECOVERY_ADMIN"))) {
                return GateEvaluationResult.deny("S8 Violation: Production execution requires RECOVERY_APPROVE_PRODUCTION capability", action);
            }
        }

        // S6 & S16: Attempt Idempotency & Duplicate Execution Prevention
        if (idempotencyKey != null && executionAttemptRepository != null) {
            Optional<ExecutionAttemptEntity> existingAttempt = executionAttemptRepository.findByIdempotencyKey(idempotencyKey);
            if (existingAttempt.isPresent()) {
                ExecutionAttemptEntity attempt = existingAttempt.get();
                if (attempt.getStatus() == ExecutionAttemptStatus.SUCCEEDED) {
                    return GateEvaluationResult.deny("S16 Violation: Execution attempt with idempotency key '" + idempotencyKey + "' already SUCCEEDED", action);
                }
                if (attempt.getStatus() == ExecutionAttemptStatus.EXECUTING) {
                    Instant now = clock.instant();
                    if (attempt.getLeaseExpiresAt() != null && attempt.getLeaseExpiresAt().isAfter(now)) {
                        return GateEvaluationResult.deny("S16 Violation: Active lease exists for idempotency key '" + idempotencyKey + "'", action);
                    }
                }
            }
        }

        // S5: Cooldown Non-Bypass: re-evaluate immediately prior to dispatch
        Instant now = clock.instant();
        if (recoveryService != null && action.getTargetResourceId() != null) {
            RecoveryServiceImpl.AntiFlappingEvaluation cooldownEval =
                    recoveryService.evaluateAntiFlapping(action.getTargetResourceId(), action.getActionType(), now);
            if (cooldownEval != null && cooldownEval.inCooldown()) {
                return GateEvaluationResult.deny("S5 Violation: " + cooldownEval.suppressionReason(), action);
            }
        }

        // S4: Policy Non-Bypass: re-evaluate pre-flight policy immediately prior to dispatch
        if (recoveryService != null && action.getTargetResourceType() != null) {
            RecoveryServiceImpl.PolicyPreFlightEvaluation policyEval = recoveryService.evaluatePolicyPreFlight(
                    action.getTargetResourceType(),
                    action.getTargetResourceName(),
                    action.getActionType(),
                    "*",
                    null
            );
            if (policyEval != null && !policyEval.allowed()) {
                return GateEvaluationResult.deny("S4 Violation: " + policyEval.blockReason(), action);
            }
        }

        log.info("Execution authorization gate PASSED for action {} on target {}", action.getId(), action.getTargetResourceName());
        return GateEvaluationResult.permit(action);
    }
}
