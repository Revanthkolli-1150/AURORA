package com.aurora.platform.recovery;

import com.aurora.platform.recovery.application.gate.ExecutionAuthorizationGate;
import com.aurora.platform.recovery.application.orchestration.ControlledExecutionOrchestrator;
import com.aurora.platform.recovery.application.verification.VerificationService;
import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.ExecutionAttemptStatus;
import com.aurora.platform.recovery.entity.OutboxEventStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryOutboxEventEntity;
import com.aurora.platform.recovery.entity.VerificationEntity;
import com.aurora.platform.recovery.entity.VerificationStatus;
import com.aurora.platform.recovery.infrastructure.actuator.SimulatedSandboxActuator;
import com.aurora.platform.recovery.infrastructure.outbox.OutboxDispatcher;
import com.aurora.platform.recovery.repository.ExecutionAttemptRepository;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryOutboxEventRepository;
import com.aurora.platform.recovery.repository.VerificationRepository;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Phase5AExecutionLifecycleTest {

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private ExecutionAttemptRepository executionAttemptRepository;

    @Mock
    private VerificationRepository verificationRepository;

    @Mock
    private RecoveryOutboxEventRepository outboxEventRepository;

    @Mock
    private ResourceService resourceService;

    @Mock
    private VerificationService verificationService;

    private SimulatedSandboxActuator sandboxActuator;
    private ExecutionAuthorizationGate authorizationGate;
    private ControlledExecutionOrchestrator orchestrator;
    private OutboxDispatcher outboxDispatcher;
    private Instant now;

    private UUID actionId;
    private UUID targetResourceId;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-09-30T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));

        sandboxActuator = new SimulatedSandboxActuator();
        sandboxActuator.reset();

        authorizationGate = new ExecutionAuthorizationGate(
                recoveryActionRepository,
                executionAttemptRepository,
                resourceService,
                null
        );
        authorizationGate.setClock(clock);

        orchestrator = new ControlledExecutionOrchestrator(
                authorizationGate,
                sandboxActuator,
                recoveryActionRepository,
                executionAttemptRepository,
                verificationService
        );
        orchestrator.setClock(clock);

        outboxDispatcher = new OutboxDispatcher(
                outboxEventRepository,
                orchestrator
        );
        outboxDispatcher.setClock(clock);

        actionId = UUID.randomUUID();
        targetResourceId = UUID.randomUUID();
    }

    private RecoveryActionEntity createApprovedAction(String environment) {
        return RecoveryActionEntity.builder()
                .id(actionId)
                .actionType("RESTART_POD")
                .targetResourceId(targetResourceId)
                .targetEnvironment(environment)
                .targetResourceType(ResourceType.POD)
                .targetResourceName("auth-worker-01")
                .target("auth-worker-01")
                .status(RecoveryActionStatus.APPROVED)
                .approvedByUserId("operator-sre")
                .approvedByCapability(environment.equalsIgnoreCase("production") ? "RECOVERY_APPROVE_PRODUCTION" : "RECOVERY_APPROVE")
                .build();
    }

    private ResourceResponse createValidResource(String environment) {
        return new ResourceResponse(
                targetResourceId, "auth-worker-01", ResourceType.POD, ResourceStatus.HEALTHY,
                environment, "k8s-node", Map.of(), now, now
        );
    }

    @Test
    @DisplayName("Clean Successful Execution: Approved action executes via sandbox and enters verification lifecycle")
    void cleanSuccessfulExecutionLifecycle() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createValidResource("staging"));
        when(executionAttemptRepository.findByIdempotencyKey("exec-key-1")).thenReturn(Optional.empty());
        when(executionAttemptRepository.countByRecoveryActionId(actionId)).thenReturn(0L);
        when(executionAttemptRepository.save(any(ExecutionAttemptEntity.class))).thenAnswer(inv -> {
            ExecutionAttemptEntity att = inv.getArgument(0);
            if (att.getId() == null) {
                att.setId(UUID.randomUUID());
            }
            return att;
        });

        VerificationEntity mockVerification = VerificationEntity.builder()
                .id(UUID.randomUUID())
                .status(VerificationStatus.SCHEDULED)
                .build();
        when(verificationService.scheduleVerification(any(), eq("system.health"), eq(100.0), eq(50.0)))
                .thenReturn(mockVerification);

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "exec-key-1");

        assertThat(attempt).isNotNull();
        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getIdempotencyKey()).isEqualTo("exec-key-1");
        assertThat(attempt.getExternalExecutionRef()).startsWith("sandbox-exec-");

        // Actuator invocation recorded
        assertThat(sandboxActuator.getRecordedInvocations()).hasSize(1);
        assertThat(sandboxActuator.getExecutionCountForIdempotencyKey("exec-key-1")).isEqualTo(1);

        // Action status updated to IN_PROGRESS
        verify(recoveryActionRepository).save(action);
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.IN_PROGRESS);

        // Verification scheduled (S11)
        verify(verificationService).scheduleVerification(any(), eq("system.health"), eq(100.0), eq(50.0));
    }

    @Test
    @DisplayName("S16 & S6: Duplicate Execution Prevention - Idempotent replay prevents multiple actuator calls and preserves state")
    void duplicateExecutionPrevention() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createValidResource("staging"));

        // Simulate existing SUCCEEDED attempt
        ExecutionAttemptEntity priorAttempt = ExecutionAttemptEntity.builder()
                .id(UUID.randomUUID())
                .recoveryAction(action)
                .idempotencyKey("idemp-key-dup")
                .status(ExecutionAttemptStatus.SUCCEEDED)
                .externalExecutionRef("sandbox-exec-prior")
                .build();
        when(executionAttemptRepository.findByIdempotencyKey("idemp-key-dup"))
                .thenReturn(Optional.of(priorAttempt));

        // When duplicate dispatch occurs, gate detects existing SUCCEEDED attempt and suppresses execution
        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "idemp-key-dup"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S16 Violation: Execution attempt with idempotency key 'idemp-key-dup' already SUCCEEDED");

        // Action status must NOT be mutated to FAILED upon replay
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.APPROVED);
        // Existing attempt must NOT be altered
        assertThat(priorAttempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        // Actuator was NEVER invoked
        assertThat(sandboxActuator.getRecordedInvocations()).isEmpty();
    }

    @Test
    @DisplayName("Finding 2: Replay of completed action preserves terminal state and prevents duplicate actuator execution")
    void replayOfCompletedActionPreservesTerminalState() {
        // Initial state: action previously completed successfully
        RecoveryActionEntity completedAction = createApprovedAction("staging");
        completedAction.setStatus(RecoveryActionStatus.COMPLETED);
        completedAction.setResult("Action executed and verified successfully");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(completedAction));

        // Replay attempt must be rejected fail-closed by S3 terminal check
        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "replay-key-completed"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3 Violation: Cannot execute action in status 'COMPLETED'");

        // Crucial invariant: RecoveryAction state remains COMPLETED (NOT mutated to FAILED)
        assertThat(completedAction.getStatus()).isEqualTo(RecoveryActionStatus.COMPLETED);
        assertThat(completedAction.getResult()).isEqualTo("Action executed and verified successfully");

        // Actuator was NOT invoked
        assertThat(sandboxActuator.getRecordedInvocations()).isEmpty();
    }

    @Test
    @DisplayName("Genuine gate failure (non-replay) transitions action to FAILED")
    void genuineGateFailureTransitionsToFailed() {
        RecoveryActionEntity unapprovedAction = createApprovedAction("staging");
        unapprovedAction.setApprovedByUserId(null); // S1 Violation: Missing authenticated operator
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(unapprovedAction));

        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "genuine-fail-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S1 Violation: Missing authenticated operator approval record");

        // Genuinely failed new execution transitions to FAILED
        assertThat(unapprovedAction.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
        assertThat(unapprovedAction.getResult()).contains("Execution blocked by authorization gate: S1 Violation");
        verify(recoveryActionRepository).save(unapprovedAction);

        // Actuator was never invoked
        assertThat(sandboxActuator.getRecordedInvocations()).isEmpty();
    }

    @Test
    @DisplayName("S10: Actuator Failure records observable terminal FAILED status on attempt and action")
    void actuatorFailureRecordsTerminalStatus() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createValidResource("staging"));
        when(executionAttemptRepository.findByIdempotencyKey("key-fail")).thenReturn(Optional.empty());
        when(executionAttemptRepository.save(any(ExecutionAttemptEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        sandboxActuator.setDefaultBehavior(SimulatedSandboxActuator.SimulationBehavior.FAILURE);

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "key-fail");

        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(attempt.getFailureReason()).contains("intentionally failed");
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
        assertThat(action.getResult()).contains("Execution failed");

        // Verification must NOT be scheduled for failed attempt
        verify(verificationService, never()).scheduleVerification(any(), any(), any(Double.class), any(Double.class));
    }

    @Test
    @DisplayName("S10: Actuator Timeout records observable terminal TIMED_OUT status on attempt and FAILED on action")
    void actuatorTimeoutRecordsTerminalStatus() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createValidResource("staging"));
        when(executionAttemptRepository.findByIdempotencyKey("key-timeout")).thenReturn(Optional.empty());
        when(executionAttemptRepository.save(any(ExecutionAttemptEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        sandboxActuator.setDefaultBehavior(SimulatedSandboxActuator.SimulationBehavior.TIMEOUT);

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "key-timeout");

        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.TIMED_OUT);
        assertThat(attempt.getFailureReason()).contains("timed out");
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
        assertThat(action.getResult()).contains("Execution timed out");
    }

    @Test
    @DisplayName("S3: Invariant - Terminal FAILED action cannot be re-executed silently and preserves FAILED state")
    void terminalFailedActionCannotBeReExecuted() {
        RecoveryActionEntity failedAction = createApprovedAction("staging");
        failedAction.setStatus(RecoveryActionStatus.FAILED);
        failedAction.setResult("Original failure reason");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(failedAction));

        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "key-retry-terminal"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3 Violation: Cannot execute action in status 'FAILED'");

        // Terminal state preserved, result not overwritten with gate message
        assertThat(failedAction.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
        assertThat(failedAction.getResult()).isEqualTo("Original failure reason");

        // Actuator was never invoked
        assertThat(sandboxActuator.getRecordedInvocations()).isEmpty();
    }
}
