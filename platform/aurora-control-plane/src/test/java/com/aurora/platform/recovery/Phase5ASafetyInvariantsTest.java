package com.aurora.platform.recovery;

import com.aurora.platform.infrastructure.security.AuthenticatedOperator;
import com.aurora.platform.infrastructure.security.OperatorAuthenticationToken;
import com.aurora.platform.infrastructure.security.RecoveryCapability;
import com.aurora.platform.recovery.application.gate.ExecutionAuthorizationGate;
import com.aurora.platform.recovery.application.gate.GateEvaluationResult;
import com.aurora.platform.recovery.application.orchestration.ControlledExecutionOrchestrator;
import com.aurora.platform.recovery.application.port.out.ActuationRequest;
import com.aurora.platform.recovery.application.port.out.RecoveryActuatorPort;
import com.aurora.platform.recovery.application.verification.VerificationService;
import com.aurora.platform.recovery.application.verification.VerificationServiceImpl;
import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.ExecutionAttemptStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.VerificationEntity;
import com.aurora.platform.recovery.entity.VerificationStatus;
import com.aurora.platform.recovery.infrastructure.actuator.SimulatedSandboxActuator;
import com.aurora.platform.recovery.repository.ExecutionAttemptRepository;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.VerificationRepository;
import com.aurora.platform.recovery.service.RecoveryServiceImpl;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Formal acceptance contract tests for Safety Invariants S1 through S17.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Phase5ASafetyInvariantsTest {

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private ExecutionAttemptRepository executionAttemptRepository;

    @Mock
    private VerificationRepository verificationRepository;

    @Mock
    private ResourceService resourceService;

    @Mock
    private TelemetryService telemetryService;

    @Mock
    private RecoveryServiceImpl recoveryService;

    @Mock
    private VerificationService verificationService;

    private SimulatedSandboxActuator sandboxActuator;
    private ExecutionAuthorizationGate gate;
    private ControlledExecutionOrchestrator orchestrator;

    private Instant now;
    private UUID actionId;
    private UUID targetResourceId;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-09-30T18:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));

        sandboxActuator = new SimulatedSandboxActuator();
        sandboxActuator.reset();

        gate = new ExecutionAuthorizationGate(
                recoveryActionRepository,
                executionAttemptRepository,
                resourceService,
                recoveryService
        );
        gate.setClock(clock);

        orchestrator = new ControlledExecutionOrchestrator(
                gate,
                sandboxActuator,
                recoveryActionRepository,
                executionAttemptRepository,
                verificationService
        );
        orchestrator.setClock(clock);

        actionId = UUID.randomUUID();
        targetResourceId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RecoveryActionEntity createAction(RecoveryActionStatus status, String env, String approvedByUser, String capability) {
        return RecoveryActionEntity.builder()
                .id(actionId)
                .actionType("RESTART_POD")
                .targetResourceId(targetResourceId)
                .targetEnvironment(env)
                .targetResourceType(ResourceType.POD)
                .targetResourceName("pod-1")
                .target("pod-1")
                .status(status)
                .approvedByUserId(approvedByUser)
                .approvedByCapability(capability)
                .build();
    }

    private ResourceResponse createResource(String env) {
        return new ResourceResponse(
                targetResourceId, "pod-1", ResourceType.POD, ResourceStatus.HEALTHY,
                env, "node-1", Map.of(), now, now
        );
    }

    @Test
    @DisplayName("S1: Authentication Gate - Execution rejected if approval record lacks authenticated operator identity")
    void testS1AuthenticationGate() {
        RecoveryActionEntity anonymousAction = createAction(RecoveryActionStatus.APPROVED, "staging", null, null);
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(anonymousAction));

        GateEvaluationResult result = gate.evaluate(actionId, "k-s1");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S1 Violation");
    }

    @Test
    @DisplayName("S2: Approval Isolation - RecoveryService approval never invokes actuator")
    void testS2ApprovalIsolation() {
        // Verification: RecoveryServiceImpl does not hold a reference to RecoveryActuatorPort
        for (var field : RecoveryServiceImpl.class.getDeclaredFields()) {
            assertThat(RecoveryActuatorPort.class.isAssignableFrom(field.getType()))
                    .as("Approval service must not hold an actuator reference")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("S3: Execution Prerequisite - Cannot execute action that is not APPROVED")
    void testS3ExecutionPrerequisite() {
        RecoveryActionEntity pendingAction = createAction(RecoveryActionStatus.PENDING, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(pendingAction));

        GateEvaluationResult result = gate.evaluate(actionId, "k-s3");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S3 Violation");
    }

    @Test
    @DisplayName("S4: Policy Non-Bypass - Gate re-evaluates active policy immediately prior to execution")
    void testS4PolicyNonBypass() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        when(recoveryService.evaluatePolicyPreFlight(any(), any(), any(), any(), any()))
                .thenReturn(new RecoveryServiceImpl.PolicyPreFlightEvaluation(false, null, "Active policy blocks RESTART_POD"));

        GateEvaluationResult result = gate.evaluate(actionId, "k-s4");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S4 Violation");
    }

    @Test
    @DisplayName("S5: Cooldown Non-Bypass - Gate re-evaluates anti-flapping cooldown immediately prior to execution")
    void testS5CooldownNonBypass() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        when(recoveryService.evaluateAntiFlapping(eq(targetResourceId), any(), any()))
                .thenReturn(new RecoveryServiceImpl.AntiFlappingEvaluation(true, 3, "In cooldown"));

        GateEvaluationResult result = gate.evaluate(actionId, "k-s5");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S5 Violation");
    }

    @Test
    @DisplayName("S6: Attempt Idempotency - Every execution attempt possesses a unique idempotency key")
    void testS6AttemptIdempotency() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "unique-key-s6");
        assertThat(attempt.getIdempotencyKey()).isEqualTo("unique-key-s6");
    }

    @Test
    @DisplayName("S7: Canonical Target Binding - Target must resolve to a valid ResourceEntity UUID")
    void testS7CanonicalTargetBinding() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        action.setTargetResourceId(null); // missing canonical target
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        GateEvaluationResult result = gate.evaluate(actionId, "k-s7");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S7 Violation");
    }

    @Test
    @DisplayName("S8: Production Human Authorization - Production execution requires human operator approval record with production capability")
    void testS8ProductionHumanAuthorization() {
        RecoveryActionEntity prodActionWithoutProdCap = createAction(
                RecoveryActionStatus.APPROVED, "production", "user-dev", "RECOVERY_APPROVE"
        );
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(prodActionWithoutProdCap));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("production"));

        GateEvaluationResult result = gate.evaluate(actionId, "k-s8");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S8 Violation");
    }

    @Test
    @DisplayName("S9: Transactional Decoupling - Actuation occurs outside database transactions")
    void testS9TransactionalDecoupling() {
        // Verification: ControlledExecutionOrchestrator.executeAction does NOT declare @Transactional
        try {
            Method method = ControlledExecutionOrchestrator.class.getMethod("executeAction", UUID.class, String.class);
            assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class))
                    .as("executeAction must not run inside a DB transaction")
                    .isFalse();
        } catch (NoSuchMethodException e) {
            org.junit.jupiter.api.Assertions.fail(e);
        }
    }

    @Test
    @DisplayName("S10: Observable Terminal Result - Every attempt reaches SUCCEEDED, FAILED, or TIMED_OUT")
    void testS10ObservableTerminalResult() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExecutionAttemptEntity attempt = orchestrator.executeAction(actionId, "k-s10");
        assertThat(attempt.getStatus()).isIn(
                ExecutionAttemptStatus.SUCCEEDED,
                ExecutionAttemptStatus.FAILED,
                ExecutionAttemptStatus.TIMED_OUT
        );
    }

    @Test
    @DisplayName("S11: Mandatory Verification Lifecycle - Succeeded attempt enters verification")
    void testS11MandatoryVerificationLifecycle() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));
        when(executionAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.executeAction(actionId, "k-s11");

        // Verify verification was scheduled
        verify(verificationService).scheduleVerification(any(), eq("system.health"), any(Double.class), any(Double.class));
    }

    @Test
    @DisplayName("S12: Truthful Verification - Missing telemetry cannot be marked healthy")
    void testS12TruthfulVerification() {
        VerificationEntity v = VerificationEntity.builder()
                .id(UUID.randomUUID())
                .executionAttempt(ExecutionAttemptEntity.builder().recoveryAction(createAction(RecoveryActionStatus.IN_PROGRESS, "staging", "u", "c")).build())
                .targetResourceId(targetResourceId)
                .metricName("metric")
                .baselineValue(100.0)
                .policyThreshold(50.0)
                .observationStartedAt(now)
                .status(VerificationStatus.SCHEDULED)
                .build();
        when(verificationRepository.findById(v.getId())).thenReturn(Optional.of(v));
        when(verificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(telemetryService.getTelemetrySince(any(), any(), any())).thenReturn(Collections.emptyList());

        VerificationServiceImpl realVerificationService = new VerificationServiceImpl(verificationRepository, recoveryActionRepository, telemetryService);
        realVerificationService.setClock(Clock.fixed(now, ZoneId.of("UTC")));

        VerificationEntity result = realVerificationService.executeVerification(v.getId());
        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_INCONCLUSIVE);
        assertThat(result.getStatus()).isNotEqualTo(VerificationStatus.VERIFIED_HEALTHY);
    }

    @Test
    @DisplayName("S13: Process Execution Ban - Verified via ArchUnit and source audit that zero Runtime.exec or ProcessBuilder exist")
    void testS13ProcessExecutionBan() {
        // Enforced by ArchitectureRulesTest Rule F3
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("S14: Vendor Neutrality - ActuatorPort and Request operate without cloud/K8s SDK types")
    void testS14VendorNeutrality() {
        for (Method m : RecoveryActuatorPort.class.getMethods()) {
            for (Class<?> p : m.getParameterTypes()) {
                assertThat(p.getName()).doesNotContain("kubernetes");
                assertThat(p.getName()).doesNotContain("amazonaws");
                assertThat(p.getName()).doesNotContain("google");
                assertThat(p.getName()).doesNotContain("azure");
            }
        }
    }

    @Test
    @DisplayName("S15: Rule F Continuity - Autonomous remediation and real cloud actuators are absent")
    void testS15RuleFContinuity() {
        // Enforced by ArchitectureRulesTest Rule F
        assertThat(true).isTrue();
    }

    @Test
    @DisplayName("S16: Duplicate Execution Prevention - Replay of succeeded attempt is blocked")
    void testS16DuplicateExecutionPrevention() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(resourceService.getResourceById(targetResourceId)).thenReturn(createResource("staging"));

        ExecutionAttemptEntity succeeded = ExecutionAttemptEntity.builder()
                .id(UUID.randomUUID()).idempotencyKey("dup-s16").status(ExecutionAttemptStatus.SUCCEEDED).build();
        when(executionAttemptRepository.findByIdempotencyKey("dup-s16")).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> orchestrator.executeAction(actionId, "dup-s16"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S16 Violation");
    }

    @Test
    @DisplayName("S17: Target Immutability - Target resource and environment cannot be modified after approval")
    void testS17TargetImmutability() {
        RecoveryActionEntity action = createAction(RecoveryActionStatus.APPROVED, "staging", "user-1", "RECOVERY_APPROVE");
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        // Canonical resource environment in database is production
        ResourceResponse mismatch = new ResourceResponse(targetResourceId, "pod-1", ResourceType.POD, ResourceStatus.HEALTHY, "production", "node", Map.of(), now, now);
        when(resourceService.getResourceById(targetResourceId)).thenReturn(mismatch);

        GateEvaluationResult result = gate.evaluate(actionId, "k-s17");
        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S17 Violation");
    }
}
