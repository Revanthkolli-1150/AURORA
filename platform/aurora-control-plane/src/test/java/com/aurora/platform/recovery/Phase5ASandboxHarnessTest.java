package com.aurora.platform.recovery;

import com.aurora.platform.infrastructure.security.AuthenticatedOperator;
import com.aurora.platform.infrastructure.security.OperatorAuthenticationToken;
import com.aurora.platform.infrastructure.security.RecoveryCapability;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.service.PolicyService;
import com.aurora.platform.recovery.application.gate.ExecutionAuthorizationGate;
import com.aurora.platform.recovery.application.gate.GateEvaluationResult;
import com.aurora.platform.recovery.application.orchestration.ControlledExecutionOrchestrator;
import com.aurora.platform.recovery.application.verification.VerificationServiceImpl;
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
import com.aurora.platform.recovery.service.RecoveryServiceImpl;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.service.TelemetryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Phase5ASandboxHarnessTest {

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
    private PolicyService policyService;

    @Mock
    private TelemetryService telemetryService;

    private SimulatedSandboxActuator sandboxActuator;
    private RecoveryServiceImpl recoveryService;
    private ExecutionAuthorizationGate authorizationGate;
    private VerificationServiceImpl verificationService;
    private ControlledExecutionOrchestrator orchestrator;
    private OutboxDispatcher outboxDispatcher;

    private Instant now;
    private UUID actionId;
    private UUID targetResourceId;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-09-30T16:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));

        sandboxActuator = new SimulatedSandboxActuator();
        sandboxActuator.reset();

        recoveryService = new RecoveryServiceImpl(
                null,
                recoveryActionRepository,
                null,
                null,
                resourceService,
                policyService
        );
        recoveryService.setClock(clock);

        authorizationGate = new ExecutionAuthorizationGate(
                recoveryActionRepository,
                executionAttemptRepository,
                resourceService,
                recoveryService
        );
        authorizationGate.setClock(clock);

        verificationService = new VerificationServiceImpl(
                verificationRepository,
                recoveryActionRepository,
                telemetryService
        );
        verificationService.setClock(clock);

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

        AuthenticatedOperator operator = new AuthenticatedOperator(
                "sre-test", "sre-test", "sre@aurora.local",
                Set.of(RecoveryCapability.RECOVERY_APPROVE, RecoveryCapability.RECOVERY_APPROVE_PRODUCTION, RecoveryCapability.RECOVERY_ADMIN)
        );
        SecurityContextHolder.getContext().setAuthentication(new OperatorAuthenticationToken(operator));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RecoveryActionEntity createApprovedAction(String env) {
        return RecoveryActionEntity.builder()
                .id(actionId)
                .actionType("RESTART_POD")
                .targetResourceId(targetResourceId)
                .targetEnvironment(env)
                .targetResourceType(ResourceType.POD)
                .targetResourceName("worker-pod-01")
                .target("worker-pod-01")
                .status(RecoveryActionStatus.APPROVED)
                .approvedByUserId("sre-test")
                .approvedByCapability(env.equalsIgnoreCase("production") ? "RECOVERY_APPROVE_PRODUCTION" : "RECOVERY_APPROVE")
                .build();
    }

    private ResourceResponse createResource(String env) {
        return new ResourceResponse(
                targetResourceId, "worker-pod-01", ResourceType.POD, ResourceStatus.HEALTHY,
                env, "k8s-node-1", Map.of(), now, now
        );
    }

    // 1. Clean successful execution
    @Test
    @DisplayName("Scenario 1: Clean successful execution through sandbox harness")
    void scenario1CleanSuccessfulExecution() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "key-sc1");

        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.SUCCEEDED);
        assertThat(sandboxActuator.getRecordedInvocations()).hasSize(1);
    }

    // 2. Actuator failure
    @Test
    @DisplayName("Scenario 2: Actuator failure records terminal FAILED status")
    void scenario2ActuatorFailure() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sandboxActuator.setDefaultBehavior(SimulatedSandboxActuator.SimulationBehavior.FAILURE);

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "key-sc2");

        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.FAILED);
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
    }

    // 3. Timeout
    @Test
    @DisplayName("Scenario 3: Actuator timeout records terminal TIMED_OUT status")
    void scenario3ActuatorTimeout() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        sandboxActuator.setDefaultBehavior(SimulatedSandboxActuator.SimulationBehavior.TIMEOUT);

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "key-sc3");

        assertThat(attempt.getStatus()).isEqualTo(ExecutionAttemptStatus.TIMED_OUT);
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
    }

    // 4. Duplicate dispatch
    @Test
    @DisplayName("Scenario 4: Duplicate dispatch is safely suppressed by idempotency key")
    void scenario4DuplicateDispatch() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        ExecutionAttemptEntity priorAttempt = ExecutionAttemptEntity.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("dup-key-sc4")
                .status(ExecutionAttemptStatus.SUCCEEDED)
                .build();
        when(executionAttemptRepository.findByIdempotencyKey("dup-key-sc4")).thenReturn(Optional.of(priorAttempt));

        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "dup-key-sc4"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S16 Violation");

        assertThat(sandboxActuator.getRecordedInvocations()).isEmpty();
    }

    // 5. Concurrent dispatch
    @Test
    @DisplayName("Scenario 5: Concurrent dispatch suppressed when active lease exists")
    void scenario5ConcurrentDispatch() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        ExecutionAttemptEntity activeAttempt = ExecutionAttemptEntity.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("lease-key-sc5")
                .status(ExecutionAttemptStatus.EXECUTING)
                .leaseExpiresAt(now.plusSeconds(30)) // active lease!
                .build();
        when(executionAttemptRepository.findByIdempotencyKey("lease-key-sc5")).thenReturn(Optional.of(activeAttempt));

        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "lease-key-sc5"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active lease exists");

        assertThat(sandboxActuator.getRecordedInvocations()).isEmpty();
    }

    // 6. Stale telemetry
    @Test
    @DisplayName("Scenario 6: Stale telemetry does not satisfy verification and fails closed")
    void scenario6StaleTelemetryFailsClosed() {
        UUID verificationId = UUID.randomUUID();
        VerificationEntity v = VerificationEntity.builder()
                .id(verificationId)
                .executionAttempt(ExecutionAttemptEntity.builder().recoveryAction(createApprovedAction("staging")).build())
                .targetResourceId(targetResourceId)
                .metricName("cpu_utilization")
                .baselineValue(95.0)
                .policyThreshold(80.0)
                .observationStartedAt(now.minusSeconds(120))
                .status(VerificationStatus.SCHEDULED)
                .build();
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Telemetry service returns no telemetry events since observation started
        when(telemetryService.getTelemetrySince(targetResourceId, "cpu_utilization", v.getObservationStartedAt()))
                .thenReturn(Collections.emptyList());

        VerificationEntity result = verificationService.executeVerification(verificationId);
        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_INCONCLUSIVE);
    }

    // 7. Healthy verification
    @Test
    @DisplayName("Scenario 7: Healthy verification confirms metric stabilized below threshold")
    void scenario7HealthyVerification() {
        UUID verificationId = UUID.randomUUID();
        RecoveryActionEntity act = createApprovedAction("staging");
        VerificationEntity v = VerificationEntity.builder()
                .id(verificationId)
                .executionAttempt(ExecutionAttemptEntity.builder().recoveryAction(act).build())
                .targetResourceId(targetResourceId)
                .metricName("cpu_utilization")
                .baselineValue(95.0)
                .policyThreshold(80.0)
                .observationStartedAt(now.minusSeconds(120))
                .status(VerificationStatus.SCHEDULED)
                .build();
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TelemetryEventResponse sample = new TelemetryEventResponse(
                UUID.randomUUID(), targetResourceId, now.minusSeconds(10),
                TelemetryType.METRIC, "cpu_utilization", 65.0, "percent", Map.of()
        );
        when(telemetryService.getTelemetrySince(targetResourceId, "cpu_utilization", v.getObservationStartedAt()))
                .thenReturn(List.of(sample));

        VerificationEntity result = verificationService.executeVerification(verificationId);
        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_HEALTHY);
        assertThat(act.getStatus()).isEqualTo(RecoveryActionStatus.COMPLETED);
    }

    // 8. Degraded verification
    @Test
    @DisplayName("Scenario 8: Degraded verification triggers terminal failure")
    void scenario8DegradedVerification() {
        UUID verificationId = UUID.randomUUID();
        RecoveryActionEntity act = createApprovedAction("staging");
        VerificationEntity v = VerificationEntity.builder()
                .id(verificationId)
                .executionAttempt(ExecutionAttemptEntity.builder().recoveryAction(act).build())
                .targetResourceId(targetResourceId)
                .metricName("cpu_utilization")
                .baselineValue(95.0)
                .policyThreshold(80.0)
                .observationStartedAt(now.minusSeconds(120))
                .status(VerificationStatus.SCHEDULED)
                .build();
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TelemetryEventResponse sample = new TelemetryEventResponse(
                UUID.randomUUID(), targetResourceId, now.minusSeconds(10),
                TelemetryType.METRIC, "cpu_utilization", 99.5, "percent", Map.of()
        );
        when(telemetryService.getTelemetrySince(targetResourceId, "cpu_utilization", v.getObservationStartedAt()))
                .thenReturn(List.of(sample));

        VerificationEntity result = verificationService.executeVerification(verificationId);
        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_DEGRADED);
        assertThat(act.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
    }

    // 9. Inconclusive verification
    @Test
    @DisplayName("Scenario 9: Inconclusive verification triggers terminal failure")
    void scenario9InconclusiveVerification() {
        UUID verificationId = UUID.randomUUID();
        RecoveryActionEntity act = createApprovedAction("staging");
        VerificationEntity v = VerificationEntity.builder()
                .id(verificationId)
                .executionAttempt(ExecutionAttemptEntity.builder().recoveryAction(act).build())
                .targetResourceId(targetResourceId)
                .metricName("cpu_utilization")
                .baselineValue(95.0)
                .policyThreshold(80.0)
                .observationStartedAt(now.minusSeconds(120))
                .status(VerificationStatus.SCHEDULED)
                .build();
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Telemetry improved to 88%, which is below 95% baseline, but still above 80% threshold
        TelemetryEventResponse sample = new TelemetryEventResponse(
                UUID.randomUUID(), targetResourceId, now.minusSeconds(10),
                TelemetryType.METRIC, "cpu_utilization", 88.0, "percent", Map.of()
        );
        when(telemetryService.getTelemetrySince(targetResourceId, "cpu_utilization", v.getObservationStartedAt()))
                .thenReturn(List.of(sample));

        VerificationEntity result = verificationService.executeVerification(verificationId);
        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_INCONCLUSIVE);
        assertThat(act.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
    }

    // 10. Policy changed after approval
    @Test
    @DisplayName("Scenario 10: Policy changed after approval causes gate to block execution")
    void scenario10PolicyChangedAfterApproval() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        // Newly introduced blocking policy at dispatch time
        PolicyResponse blockPolicy = new PolicyResponse(
                UUID.randomUUID(), "Freeze Restarts", ResourceType.POD,
                "*", null, "PROHIBIT_RESTART_POD", true, now, now
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(blockPolicy));

        GateEvaluationResult result = authorizationGate.evaluate(actionId, "key-sc10");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S4 Violation");
    }

    // 11. Cooldown becomes active
    @Test
    @DisplayName("Scenario 11: Cooldown becoming active after approval blocks execution")
    void scenario11CooldownBecomesActive() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        // Recent failure history on target
        RecoveryActionEntity recentFail1 = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .targetResourceId(targetResourceId)
                .actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minusSeconds(300))
                .build();
        RecoveryActionEntity recentFail2 = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .targetResourceId(targetResourceId)
                .actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minusSeconds(100))
                .build();
        when(recoveryActionRepository.findByTargetResourceIdAndActionType(targetResourceId, "RESTART_POD"))
                .thenReturn(List.of(recentFail1, recentFail2));

        GateEvaluationResult result = authorizationGate.evaluate(actionId, "key-sc11");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S5 Violation");
    }

    // 12. Target becomes invalid
    @Test
    @DisplayName("Scenario 12: Target becoming invalid or deleted blocks execution")
    void scenario12TargetBecomesInvalid() {
        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(null); // Target deleted!

        GateEvaluationResult result = authorizationGate.evaluate(actionId, "key-sc12");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S7 Violation");
    }

    // 13. Application restart after outbox commit
    @Test
    @DisplayName("Scenario 13: Application restart after outbox commit preserves dispatchability")
    void scenario13ApplicationRestartPreservesOutboxDispatchability() {
        RecoveryOutboxEventEntity pendingEvent = RecoveryOutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(actionId)
                .eventType("RECOVERY_ACTION_APPROVED")
                .idempotencyKey("outbox-action-" + actionId + "-v0")
                .payload("{}")
                .status(OutboxEventStatus.PENDING)
                .createdAt(now.minusSeconds(30))
                .build();

        when(outboxEventRepository.findPendingForUpdate(eq(OutboxEventStatus.PENDING), any()))
                .thenReturn(List.of(pendingEvent));
        when(outboxEventRepository.findById(pendingEvent.getId())).thenReturn(Optional.of(pendingEvent));

        RecoveryActionEntity action = createApprovedAction("staging");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int dispatched = outboxDispatcher.dispatchPendingEvents();

        assertThat(dispatched).isEqualTo(1);
        assertThat(pendingEvent.getStatus()).isEqualTo(OutboxEventStatus.SENT);
        assertThat(sandboxActuator.getRecordedInvocations()).hasSize(1);
    }

    // 14. Dispatcher restart recovers stuck PROCESSING events
    @Test
    @DisplayName("Scenario 14: Dispatcher restart recovers stuck PROCESSING events")
    void scenario14DispatcherCrashRecovery() {
        RecoveryOutboxEventEntity stuckEvent = RecoveryOutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateId(actionId)
                .status(OutboxEventStatus.PROCESSING)
                .createdAt(now.minus(Duration.ofMinutes(10))) // stuck longer than timeout!
                .build();

        when(outboxEventRepository.findByStatusOrderByCreatedAtAsc(OutboxEventStatus.PROCESSING))
                .thenReturn(List.of(stuckEvent));

        outboxDispatcher.recoverStuckProcessingEvents();

        assertThat(stuckEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
    }

    // 15. Failed action cannot silently retry
    @Test
    @DisplayName("Scenario 15: Failed action is terminal and cannot silently re-execute")
    void scenario15FailedActionCannotSilentlyRetry() {
        RecoveryActionEntity action = createApprovedAction("staging");
        action.setStatus(RecoveryActionStatus.FAILED);
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "key-sc15"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3 Violation");
    }

    // 16. Staging/production target isolation
    @Test
    @DisplayName("Scenario 16: Staging and production target isolation prevents cross-environment side effects")
    void scenario16StagingProductionTargetIsolation() {
        UUID stagingResId = UUID.randomUUID();
        UUID prodResId = UUID.randomUUID();

        ResourceResponse stagingRes = new ResourceResponse(stagingResId, "svc", ResourceType.SERVICE, ResourceStatus.HEALTHY, "staging", "n1", Map.of(), now, now);
        ResourceResponse prodRes = new ResourceResponse(prodResId, "svc", ResourceType.SERVICE, ResourceStatus.HEALTHY, "production", "n2", Map.of(), now, now);

        when(resourceService.getResourceById(stagingResId)).thenReturn(stagingRes);
        when(resourceService.getResourceById(prodResId)).thenReturn(prodRes);

        RecoveryActionEntity stagingAction = RecoveryActionEntity.builder()
                .id(UUID.randomUUID()).actionType("RESTART_POD").targetResourceId(stagingResId).targetEnvironment("staging")
                .targetResourceType(ResourceType.SERVICE).targetResourceName("svc").status(RecoveryActionStatus.APPROVED)
                .approvedByUserId("sre").approvedByCapability("RECOVERY_APPROVE").build();

        when(recoveryActionRepository.findById(stagingAction.getId())).thenReturn(Optional.of(stagingAction));

        GateEvaluationResult result = authorizationGate.evaluate(stagingAction.getId(), "key-iso");
        assertThat(result.allowed()).isTrue();
        assertThat(result.action().getTargetResourceId()).isEqualTo(stagingResId);
        assertThat(result.action().getTargetResourceId()).isNotEqualTo(prodResId);
    }
}
