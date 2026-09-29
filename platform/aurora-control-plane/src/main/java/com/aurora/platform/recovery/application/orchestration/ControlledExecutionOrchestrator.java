package com.aurora.platform.recovery.application.orchestration;

import com.aurora.platform.recovery.application.gate.ExecutionAuthorizationGate;
import com.aurora.platform.recovery.application.gate.GateEvaluationResult;
import com.aurora.platform.recovery.application.port.out.ActuationRequest;
import com.aurora.platform.recovery.application.port.out.ActuationResult;
import com.aurora.platform.recovery.application.port.out.ActuationStatus;
import com.aurora.platform.recovery.application.port.out.RecoveryActuatorPort;
import com.aurora.platform.recovery.application.verification.VerificationService;
import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.ExecutionAttemptStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.VerificationEntity;
import com.aurora.platform.recovery.repository.ExecutionAttemptRepository;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates controlled actuation through the Execution Authorization Gate and RecoveryActuatorPort.
 * Enforces transactional decoupling (S9), observable terminal results (S10),
 * and mandatory verification lifecycles (S11).
 */
@Service
public class ControlledExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ControlledExecutionOrchestrator.class);

    private final ExecutionAuthorizationGate authorizationGate;
    private final RecoveryActuatorPort actuatorPort;
    private final RecoveryActionRepository recoveryActionRepository;
    private final ExecutionAttemptRepository executionAttemptRepository;
    private final VerificationService verificationService;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public ControlledExecutionOrchestrator(
            ExecutionAuthorizationGate authorizationGate,
            RecoveryActuatorPort actuatorPort,
            RecoveryActionRepository recoveryActionRepository,
            ExecutionAttemptRepository executionAttemptRepository,
            VerificationService verificationService) {
        this.authorizationGate = authorizationGate;
        this.actuatorPort = actuatorPort;
        this.recoveryActionRepository = recoveryActionRepository;
        this.executionAttemptRepository = executionAttemptRepository;
        this.verificationService = verificationService;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Executes an approved recovery action through the authorization gate and actuator port.
     */
    public ExecutionAttemptEntity executeAction(UUID actionId, String idempotencyKey) {
        log.info("Controlled execution requested for action {} (idempotencyKey={})", actionId, idempotencyKey);

        // 1. Evaluate safety gate (S1-S17)
        GateEvaluationResult gateResult = authorizationGate.evaluate(actionId, idempotencyKey);
        if (!gateResult.allowed()) {
            log.warn("Execution authorization gate REJECTED action {}: {}", actionId, gateResult.rejectionReason());
            if (gateResult.action() != null && !gateResult.isReplayOrTerminal()) {
                RecoveryActionEntity act = gateResult.action();
                act.setStatus(RecoveryActionStatus.FAILED);
                act.setResult("Execution blocked by authorization gate: " + gateResult.rejectionReason());
                recoveryActionRepository.save(act);
            }
            throw new IllegalStateException("Execution rejected by gate: " + gateResult.rejectionReason());
        }

        RecoveryActionEntity action = gateResult.action();

        // 2. Check for existing attempt with same idempotency key (S6 & S16)
        Optional<ExecutionAttemptEntity> existingAttempt = executionAttemptRepository.findByIdempotencyKey(idempotencyKey);
        if (existingAttempt.isPresent()) {
            ExecutionAttemptEntity attempt = existingAttempt.get();
            if (attempt.getStatus() == ExecutionAttemptStatus.SUCCEEDED) {
                log.info("Idempotent replay: attempt with key {} already succeeded", idempotencyKey);
                return attempt;
            }
        }

        // 3. Prepare execution attempt record
        long attemptCount = executionAttemptRepository.countByRecoveryActionId(action.getId());
        Instant now = clock.instant();

        ExecutionAttemptEntity attempt = ExecutionAttemptEntity.builder()
                .recoveryAction(action)
                .attemptNumber((int) attemptCount + 1)
                .idempotencyKey(idempotencyKey)
                .status(ExecutionAttemptStatus.DISPATCHED)
                .dispatchedAt(now)
                .build();
        attempt = executionAttemptRepository.save(attempt);

        action.setStatus(RecoveryActionStatus.IN_PROGRESS);
        recoveryActionRepository.save(action);

        // 4. Build vendor-neutral ActuationRequest
        ActuationRequest request = new ActuationRequest(
                action.getId(),
                attempt.getId(),
                idempotencyKey,
                action.getTargetResourceId(),
                action.getTargetEnvironment(),
                action.getTargetResourceType(),
                action.getTargetResourceName(),
                action.getActionType(),
                Collections.emptyMap(),
                Duration.ofSeconds(60)
        );

        // 5. Invoke actuator (S9: Physical execution occurs outside DB transaction)
        ActuationResult result;
        try {
            attempt.setStatus(ExecutionAttemptStatus.EXECUTING);
            executionAttemptRepository.save(attempt);

            result = actuatorPort.execute(request);
        } catch (Exception ex) {
            log.error("Actuator threw unexpected exception for action {}", actionId, ex);
            result = ActuationResult.failure(null, "Actuator exception: " + ex.getMessage(), null);
        }

        // 6. Record observable terminal result (S10)
        now = clock.instant();
        attempt.setCompletedAt(now);
        attempt.setExternalExecutionRef(result.externalExecutionReference());
        attempt.setRawResponsePayload(result.rawPayload());

        if (result.status() == ActuationStatus.SUCCESS) {
            attempt.setStatus(ExecutionAttemptStatus.SUCCEEDED);
            executionAttemptRepository.save(attempt);

            // S11: Mandatory verification lifecycle
            try {
                VerificationEntity verification = verificationService.scheduleVerification(
                        attempt,
                        "system.health",
                        100.0,
                        50.0
                );
                log.info("Scheduled verification {} for attempt {}", verification.getId(), attempt.getId());
            } catch (Exception ex) {
                log.error("Failed to schedule verification for attempt {}", attempt.getId(), ex);
            }
        } else if (result.status() == ActuationStatus.TIMEOUT) {
            attempt.setStatus(ExecutionAttemptStatus.TIMED_OUT);
            attempt.setFailureReason(result.message());
            executionAttemptRepository.save(attempt);

            action.setStatus(RecoveryActionStatus.FAILED);
            action.setCompletedAt(now);
            action.setResult("Execution timed out: " + result.message());
            recoveryActionRepository.save(action);
        } else {
            attempt.setStatus(ExecutionAttemptStatus.FAILED);
            attempt.setFailureReason(result.message());
            executionAttemptRepository.save(attempt);

            action.setStatus(RecoveryActionStatus.FAILED);
            action.setCompletedAt(now);
            action.setResult("Execution failed: " + result.message());
            recoveryActionRepository.save(action);
        }

        return attempt;
    }
}
