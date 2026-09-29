package com.aurora.platform.recovery.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.service.PolicyService;
import com.aurora.platform.recovery.dto.CreateRecoveryActionRequest;
import com.aurora.platform.recovery.dto.CreateRecoveryPlanRequest;
import com.aurora.platform.recovery.dto.RecoveryActionResponse;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryPlanRepository;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    @Mock
    private RecoveryPlanRepository recoveryPlanRepository;

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private IncidentService incidentService;

    @Mock
    private RcaAnalysisService rcaAnalysisService;

    @Mock
    private ResourceService resourceService;

    @Mock
    private PolicyService policyService;

    private RecoveryServiceImpl recoveryService;


    @BeforeEach
    void setUp() {
        recoveryService = new RecoveryServiceImpl(
                recoveryPlanRepository,
                recoveryActionRepository,
                incidentService,
                rcaAnalysisService,
                resourceService,
                policyService
        );
    }

    @Test
    @DisplayName("Should retrieve recovery plan and actions for a valid incident")
    void shouldGetRecoveryPlanForIncident() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .reasoning("Restart degraded pod replicas to clear thread starvation")
                .confidence(0.92)
                .risk(RecoveryRisk.LOW)
                .approvalRequired(false)
                .createdAt(Instant.now())
                .build();

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .recoveryPlan(plan)
                .actionType("RESTART_POD")
                .target("auth-service-pod-xyz")
                .status(RecoveryActionStatus.PENDING)
                .build();

        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));
        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of(action));

        RecoveryPlanResponse response = recoveryService.getRecoveryPlanByIncidentId(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(planId);
        assertThat(response.incidentId()).isEqualTo(incidentId);
        assertThat(response.risk()).isEqualTo(RecoveryRisk.LOW);
        assertThat(response.actions()).hasSize(1);
        assertThat(response.actions().get(0).actionType()).isEqualTo("RESTART_POD");
    }

    @Test
    @DisplayName("Should generate recovery plan deterministically for conclusive RCA with database cause")
    void shouldGenerateRecoveryPlanForDatabaseCause() {
        UUID incidentId = UUID.randomUUID();
        UUID incidentResourceId = UUID.randomUUID();
        UUID dbResourceId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        IncidentResponse incident = new IncidentResponse(
                incidentId,
                incidentResourceId,
                "API Latency Spike",
                "High API response latency",
                IncidentSeverity.HIGH,
                IncidentStatus.DIAGNOSED,
                0.85,
                null,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );

        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.empty());

        ResourceResponse dbResource = new ResourceResponse(
                dbResourceId,
                "postgres-primary",
                ResourceType.DATABASE,
                ResourceStatus.DEGRADED,
                "production",
                "pg-host-01",
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        when(resourceService.getResourceById(dbResourceId)).thenReturn(dbResource);

        RcaCandidateResponse candidate = new RcaCandidateResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                dbResourceId,
                "db_connection_utilization",
                "Connection pool saturation on postgres-primary",
                0.85,
                1,
                "Direct dependency saturated",
                true,
                Instant.now(),
                List.of()
        );

        RcaAnalysisResponse rca = new RcaAnalysisResponse(
                UUID.randomUUID(),
                incidentId,
                RcaAnalysisStatus.COMPLETED,
                incidentResourceId,
                "Primary cause: connection pool saturation",
                0.85,
                "VERY_HIGH",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                List.of(candidate)
        );
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(rca);

        RecoveryPlanEntity savedPlan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .reasoning("Deterministic recovery plan synthesized")
                .confidence(0.85)
                .risk(RecoveryRisk.HIGH)
                .approvalRequired(true)
                .createdAt(Instant.now())
                .build();
        when(recoveryPlanRepository.save(any(RecoveryPlanEntity.class))).thenReturn(savedPlan);

        RecoveryActionEntity act1 = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .recoveryPlan(savedPlan)
                .actionType("FLUSH_CACHE")
                .target("postgres-primary")
                .status(RecoveryActionStatus.PENDING)
                .build();
        RecoveryActionEntity act2 = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .recoveryPlan(savedPlan)
                .actionType("RESTART_POOL")
                .target("postgres-primary")
                .status(RecoveryActionStatus.PENDING)
                .build();

        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of(act1, act2));

        RecoveryPlanResponse response = recoveryService.generateRecoveryPlan(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.incidentId()).isEqualTo(incidentId);
        assertThat(response.actions()).hasSize(2);
        assertThat(response.actions().get(0).actionType()).isEqualTo("FLUSH_CACHE");
        assertThat(response.actions().get(1).actionType()).isEqualTo("RESTART_POOL");
        assertThat(response.approvalRequired()).isTrue(); // Enforced for production environment and DB
    }

    @Test
    @DisplayName("Should generate advisory plan when RCA confidence is insufficient")
    void shouldGenerateAdvisoryPlanWhenRcaInconclusive() {
        UUID incidentId = UUID.randomUUID();
        UUID incidentResourceId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        IncidentResponse incident = new IncidentResponse(
                incidentId,
                incidentResourceId,
                "Transient network drop",
                "Intermittent failures",
                IncidentSeverity.LOW,
                IncidentStatus.INVESTIGATING,
                null,
                null,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );

        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.empty());

        RcaAnalysisResponse rca = new RcaAnalysisResponse(
                UUID.randomUUID(),
                incidentId,
                RcaAnalysisStatus.INSUFFICIENT_EVIDENCE,
                incidentResourceId,
                "No candidate root cause found",
                0.15,
                "LOW",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                List.of()
        );
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(rca);

        RecoveryPlanEntity savedPlan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .reasoning("Automated recovery proposal suspended; manual diagnostic investigation required")
                .confidence(0.15)
                .risk(RecoveryRisk.CRITICAL)
                .approvalRequired(true)
                .createdAt(Instant.now())
                .build();
        when(recoveryPlanRepository.save(any(RecoveryPlanEntity.class))).thenReturn(savedPlan);

        RecoveryActionEntity manualAct = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .recoveryPlan(savedPlan)
                .actionType("MANUAL_INVESTIGATION")
                .target(incidentResourceId.toString())
                .status(RecoveryActionStatus.PENDING)
                .result("Operator manual triage required; automated recovery withheld.")
                .build();

        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of(manualAct));

        RecoveryPlanResponse response = recoveryService.generateRecoveryPlan(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.risk()).isEqualTo(RecoveryRisk.CRITICAL);
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.actions()).hasSize(1);
        assertThat(response.actions().get(0).actionType()).isEqualTo("MANUAL_INVESTIGATION");
    }

    @Test
    @DisplayName("Should return existing plan idempotently on repeated generation requests")
    void shouldReturnExistingPlanIdempotently() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        IncidentResponse incident = new IncidentResponse(
                incidentId,
                UUID.randomUUID(),
                "Title",
                "Desc",
                IncidentSeverity.MEDIUM,
                IncidentStatus.DIAGNOSED,
                null,
                null,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);

        RecoveryPlanEntity existingPlan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .reasoning("Existing plan")
                .confidence(0.9)
                .risk(RecoveryRisk.LOW)
                .approvalRequired(false)
                .createdAt(Instant.now())
                .build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(existingPlan));
        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of());

        RecoveryPlanResponse response = recoveryService.generateRecoveryPlan(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(planId);
        verify(rcaAnalysisService, never()).getLatestAnalysisByIncidentId(any());
        verify(recoveryPlanRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should approve recovery action successfully")
    void shouldApproveRecoveryAction() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId)
                .recoveryPlan(plan)
                .actionType("SCALE_OUT")
                .target("auth-service")
                .status(RecoveryActionStatus.PENDING)
                .build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(recoveryActionRepository.save(any(RecoveryActionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        RecoveryActionResponse response = recoveryService.approveRecoveryAction(incidentId, actionId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(actionId);
        assertThat(response.status()).isEqualTo(RecoveryActionStatus.APPROVED);
        assertThat(response.result()).contains("Approved by human operator");
        verify(recoveryActionRepository).save(action);
    }

    @Test
    @DisplayName("Should throw IllegalStateException when approving already completed or failed action")
    void shouldThrowExceptionWhenApprovingNonPendingAction() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId)
                .recoveryPlan(plan)
                .actionType("SCALE_OUT")
                .target("auth-service")
                .status(RecoveryActionStatus.SUCCESS)
                .build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        assertThatThrownBy(() -> recoveryService.approveRecoveryAction(incidentId, actionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot approve recovery action in status: SUCCESS");
    }

    @Test
    @DisplayName("Should create recovery plan using DTO overload")
    void shouldCreateRecoveryPlanUsingDto() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);

        CreateRecoveryPlanRequest request = new CreateRecoveryPlanRequest(
                incidentId,
                "Manual plan reason",
                0.90,
                RecoveryRisk.LOW,
                false,
                List.of(new CreateRecoveryActionRequest("FLUSH_CACHE", "redis-01", RecoveryActionStatus.PENDING, null))
        );

        RecoveryPlanEntity savedPlan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .reasoning(request.reasoning())
                .confidence(request.confidence())
                .risk(request.risk())
                .approvalRequired(false)
                .createdAt(Instant.now())
                .build();
        when(recoveryPlanRepository.save(any(RecoveryPlanEntity.class))).thenReturn(savedPlan);

        RecoveryActionEntity act = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .recoveryPlan(savedPlan)
                .actionType("FLUSH_CACHE")
                .target("redis-01")
                .status(RecoveryActionStatus.PENDING)
                .build();
        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of(act));

        RecoveryPlanResponse response = recoveryService.createRecoveryPlan(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(planId);
        assertThat(response.actions()).hasSize(1);
    }

    // ==========================================
    // POLICY TESTS
    // ==========================================

    @Test
    @DisplayName("POLICY: Applicable policy loaded and allows matching proposal")
    void shouldLoadApplicablePolicyAndAllowMatchingProposal() {
        UUID incidentId = UUID.randomUUID();
        UUID podId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        IncidentResponse incident = new IncidentResponse(
                incidentId, podId, "Pod High Memory", "Pod memory saturated",
                IncidentSeverity.HIGH, IncidentStatus.DIAGNOSED, 0.85, null,
                Instant.now(), null, Instant.now(), Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.empty());

        ResourceResponse podResource = new ResourceResponse(
                podId, "auth-worker-pod", ResourceType.POD, ResourceStatus.DEGRADED,
                "staging", "k8s-node-1", Map.of(), Instant.now(), Instant.now()
        );
        when(resourceService.getResourceById(podId)).thenReturn(podResource);

        RcaCandidateResponse candidate = new RcaCandidateResponse(
                UUID.randomUUID(), UUID.randomUUID(), podId, "memory_usage",
                "Memory leak in worker thread", 0.88, 1, "Heap memory exceeded",
                true, Instant.now(), List.of()
        );
        RcaAnalysisResponse rca = new RcaAnalysisResponse(
                UUID.randomUUID(), incidentId, RcaAnalysisStatus.COMPLETED, podId,
                "Memory leak diagnosed", 0.88, "VERY_HIGH", Instant.now(), Instant.now(), Instant.now(),
                List.of(candidate)
        );
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(rca);

        // Policy allows RESTART_POD
        PolicyResponse policy = new PolicyResponse(
                UUID.randomUUID(), "Allow Pod Restarts", ResourceType.POD,
                "memory_usage", 95.0, "RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(policy));

        RecoveryPlanEntity savedPlan = RecoveryPlanEntity.builder()
                .id(planId).incidentId(incidentId).reasoning("Plan").confidence(0.88)
                .risk(RecoveryRisk.LOW).approvalRequired(false).createdAt(Instant.now()).build();
        when(recoveryPlanRepository.save(any(RecoveryPlanEntity.class))).thenReturn(savedPlan);

        RecoveryActionEntity act = RecoveryActionEntity.builder()
                .id(UUID.randomUUID()).recoveryPlan(savedPlan).actionType("RESTART_POD")
                .target("auth-worker-pod").status(RecoveryActionStatus.PENDING).build();
        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of(act));

        RecoveryPlanResponse response = recoveryService.generateRecoveryPlan(incidentId);

        assertThat(response).isNotNull();
        verify(policyService).getActivePolicies(ResourceType.POD);
        assertThat(response.actions()).isNotEmpty();
        assertThat(response.actions().get(0).actionType()).isEqualTo("RESTART_POD");
    }

    @Test
    @DisplayName("POLICY: Violating policy blocks proposal and preserves explicit reason")
    void shouldBlockProposalAndPreserveReasonWhenViolatingPolicyMatches() {
        UUID incidentId = UUID.randomUUID();
        UUID podId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        IncidentResponse incident = new IncidentResponse(
                incidentId, podId, "Pod CPU Spike", "Pod CPU 99%",
                IncidentSeverity.HIGH, IncidentStatus.DIAGNOSED, 0.85, null,
                Instant.now(), null, Instant.now(), Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.empty());

        ResourceResponse podResource = new ResourceResponse(
                podId, "auth-worker-pod", ResourceType.POD, ResourceStatus.DEGRADED,
                "staging", "k8s-node-1", Map.of(), Instant.now(), Instant.now()
        );
        when(resourceService.getResourceById(podId)).thenReturn(podResource);

        RcaCandidateResponse candidate = new RcaCandidateResponse(
                UUID.randomUUID(), UUID.randomUUID(), podId, "cpu_utilization",
                "CPU saturation", 0.90, 1, "CPU high",
                true, Instant.now(), List.of()
        );
        RcaAnalysisResponse rca = new RcaAnalysisResponse(
                UUID.randomUUID(), incidentId, RcaAnalysisStatus.COMPLETED, podId,
                "CPU saturation diagnosed", 0.90, "VERY_HIGH", Instant.now(), Instant.now(), Instant.now(),
                List.of(candidate)
        );
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(rca);

        // Policy PROHIBITS RESTART_POD on POD
        PolicyResponse prohibitPolicy = new PolicyResponse(
                UUID.randomUUID(), "No Pod Restarts Policy", ResourceType.POD,
                "cpu_utilization", null, "PROHIBIT_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(prohibitPolicy));

        RecoveryPlanEntity savedPlan = RecoveryPlanEntity.builder()
                .id(planId).incidentId(incidentId).reasoning("Plan").confidence(0.90)
                .risk(RecoveryRisk.CRITICAL).approvalRequired(true).createdAt(Instant.now()).build();
        when(recoveryPlanRepository.save(any(RecoveryPlanEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(recoveryActionRepository.save(any(RecoveryActionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recoveryActionRepository.findByRecoveryPlanId(any())).thenAnswer(inv -> {
            RecoveryActionEntity manualAct = RecoveryActionEntity.builder()
                    .id(UUID.randomUUID()).recoveryPlan(savedPlan).actionType("MANUAL_INVESTIGATION")
                    .target("auth-worker-pod").status(RecoveryActionStatus.PENDING)
                    .result("Blocked by policy 'No Pod Restarts Policy': action 'RESTART_POD' is prohibited for resource type POD on metric 'cpu_utilization'")
                    .build();
            return List.of(manualAct);
        });

        RecoveryPlanResponse response = recoveryService.generateRecoveryPlan(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.risk()).isEqualTo(RecoveryRisk.CRITICAL);
        assertThat(response.approvalRequired()).isTrue();
        assertThat(response.reasoning()).contains("POLICY BLOCKED");
        assertThat(response.reasoning()).contains("No Pod Restarts Policy");
        assertThat(response.actions().get(0).actionType()).isEqualTo("MANUAL_INVESTIGATION");
        assertThat(response.actions().get(0).result()).contains("Blocked by policy 'No Pod Restarts Policy'");
    }

    @Test
    @DisplayName("POLICY: Disabled policy is ignored during evaluation")
    void shouldIgnoreDisabledPolicy() {
        PolicyResponse disabledPolicy = new PolicyResponse(
                UUID.randomUUID(), "Disabled Policy", ResourceType.POD,
                "cpu_utilization", null, "PROHIBIT_RESTART_POD", false, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(disabledPolicy));

        RecoveryServiceImpl.PolicyPreFlightEvaluation eval = recoveryService.evaluatePolicyPreFlight(
                ResourceType.POD, "pod-01", "RESTART_POD", "cpu_utilization", 90.0
        );

        assertThat(eval.allowed()).isTrue();
        assertThat(eval.blockReason()).isNull();
    }

    @Test
    @DisplayName("POLICY: Missing policy preserves deterministic recovery behavior without inventing defaults")
    void shouldPreserveDeterministicBehaviorWhenPolicyMissing() {
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(Collections.emptyList());

        RecoveryServiceImpl.PolicyPreFlightEvaluation eval = recoveryService.evaluatePolicyPreFlight(
                ResourceType.POD, "pod-01", "RESTART_POD", "cpu_utilization", 90.0
        );

        assertThat(eval.allowed()).isTrue();
        assertThat(eval.blockReason()).isNull();
    }

    @Test
    @DisplayName("POLICY: Policy for different resource type is not applied")
    void shouldNotApplyPolicyForDifferentResourceType() {
        PolicyResponse dbPolicy = new PolicyResponse(
                UUID.randomUUID(), "DB Only Policy", ResourceType.DATABASE,
                "cpu_utilization", null, "PROHIBIT_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(Collections.emptyList());

        RecoveryServiceImpl.PolicyPreFlightEvaluation eval = recoveryService.evaluatePolicyPreFlight(
                ResourceType.POD, "pod-01", "RESTART_POD", "cpu_utilization", 90.0
        );

        assertThat(eval.allowed()).isTrue();
    }

    @Test
    @DisplayName("POLICY: Threshold violation blocks proposal when observed value exceeds threshold")
    void shouldBlockProposalWhenObservedValueExceedsThreshold() {
        PolicyResponse thresholdPolicy = new PolicyResponse(
                UUID.randomUUID(), "CPU Ceiling Policy", ResourceType.POD,
                "cpu_utilization", 80.0, "PROHIBIT_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(thresholdPolicy));

        // Observed value 95.0 exceeds threshold 80.0 -> BLOCKED
        RecoveryServiceImpl.PolicyPreFlightEvaluation eval = recoveryService.evaluatePolicyPreFlight(
                ResourceType.POD, "pod-01", "RESTART_POD", "cpu_utilization", 95.0
        );

        assertThat(eval.allowed()).isFalse();
        assertThat(eval.blockReason()).contains("CPU Ceiling Policy");
        assertThat(eval.blockReason()).contains("80.00");
    }

    // ==========================================
    // COOLDOWN TESTS
    // ==========================================

    @Test
    @DisplayName("COOLDOWN: First failure does not trigger cooldown")
    void shouldNotTriggerCooldownOnFirstFailure() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        RecoveryActionEntity failure1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(20)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(failure1));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isFalse();
        assertThat(eval.failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("COOLDOWN: Second failure within 1 hour triggers cooldown and suppresses proposal")
    void shouldTriggerCooldownOnSecondFailureWithinOneHour() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        recoveryService.setClock(Clock.fixed(now, ZoneId.of("UTC")));

        RecoveryActionEntity failure1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(40)))
                .build();
        RecoveryActionEntity failure2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(10)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(failure1, failure2));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isTrue();
        assertThat(eval.failureCount()).isEqualTo(2);
        assertThat(eval.suppressionReason()).contains("Suppressed by anti-flapping cooldown");
        assertThat(eval.suppressionReason()).contains("2 failed recovery attempts");
    }

    @Test
    @DisplayName("COOLDOWN: Two failures outside 1-hour window do not trigger cooldown")
    void shouldNotTriggerCooldownWhenFailuresOutsideOneHour() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        RecoveryActionEntity oldFailure1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(90)))
                .build();
        RecoveryActionEntity oldFailure2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(70)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(oldFailure1, oldFailure2));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isFalse();
        assertThat(eval.failureCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("COOLDOWN: Cooldown expires after 1-hour window passes")
    void shouldExpireCooldownAfterOneHourWindowPasses() {
        Instant t0 = Instant.parse("2026-09-29T10:00:00Z");
        RecoveryActionEntity f1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(t0)
                .build();
        RecoveryActionEntity f2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(t0.plus(Duration.ofMinutes(10)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(f1, f2));

        // At T0 + 30m: in cooldown
        Instant withinWindow = t0.plus(Duration.ofMinutes(30));
        RecoveryServiceImpl.AntiFlappingEvaluation evalActive =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", withinWindow);
        assertThat(evalActive.inCooldown()).isTrue();

        // At T0 + 75m: both failures are older than 1 hour, cooldown expired
        Instant expiredTime = t0.plus(Duration.ofMinutes(75));
        RecoveryServiceImpl.AntiFlappingEvaluation evalExpired =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", expiredTime);
        assertThat(evalExpired.inCooldown()).isFalse();
        assertThat(evalExpired.failureCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("COOLDOWN: Successful recovery resets failure history")
    void shouldResetFailureHistoryUponSuccessfulRecovery() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        RecoveryActionEntity failure1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(45)))
                .build();
        RecoveryActionEntity success = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.SUCCESS)
                .completedAt(now.minus(Duration.ofMinutes(30)))
                .build();
        RecoveryActionEntity failure2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(15)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(failure1, success, failure2));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        // Failure1 was prior to success, so only failure2 counts after success -> count = 1 -> not in cooldown
        assertThat(eval.inCooldown()).isFalse();
        assertThat(eval.failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("COOLDOWN: Different resources do not share cooldown state")
    void shouldNotShareCooldownAcrossDifferentResources() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        when(recoveryActionRepository.findByTargetAndActionType("resource-b", "RESTART_POD"))
                .thenReturn(Collections.emptyList());

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("resource-b", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isFalse();
        assertThat(eval.failureCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("COOLDOWN: Different action types do not share cooldown state")
    void shouldNotShareCooldownAcrossDifferentActionTypes() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "SCALE_OUT"))
                .thenReturn(Collections.emptyList());

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "SCALE_OUT", now);

        assertThat(eval.inCooldown()).isFalse();
        assertThat(eval.failureCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("COOLDOWN: Cannot approve recovery action when target is in cooldown")
    void shouldRejectApprovalWhenActionIsInCooldown() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        Instant now = Instant.now();
        recoveryService.setClock(Clock.fixed(now, ZoneId.of("UTC")));

        when(incidentService.existsById(incidentId)).thenReturn(true);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder().id(planId).incidentId(incidentId).build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId).recoveryPlan(plan).actionType("RESTART_POD")
                .target("auth-service").status(RecoveryActionStatus.PENDING).build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        RecoveryActionEntity f1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED).completedAt(now.minus(Duration.ofMinutes(20))).build();
        RecoveryActionEntity f2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED).completedAt(now.minus(Duration.ofMinutes(10))).build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(f1, f2));

        assertThatThrownBy(() -> recoveryService.approveRecoveryAction(incidentId, actionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in anti-flapping cooldown");
    }

    // ==========================================
    // EDGE CASES: COOLDOWN & BOUNDARIES
    // ==========================================

    @Test
    @DisplayName("COOLDOWN EDGE CASE 1: Failure exactly one hour old is included in window [now - 1h, now]")
    void shouldIncludeFailureExactlyOneHourOld() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        Instant exactOneHourAgo = now.minus(Duration.ofHours(1));

        RecoveryActionEntity f1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(exactOneHourAgo)
                .build();
        RecoveryActionEntity f2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(15)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(f1, f2));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isTrue();
        assertThat(eval.failureCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("COOLDOWN EDGE CASE 2 & 3: Just inside 1h is included, just outside 1h is excluded")
    void shouldHandleOneHourWindowBoundariesPrecision() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        Instant justInside = now.minus(Duration.ofHours(1)).plusMillis(1);
        Instant justOutside = now.minus(Duration.ofHours(1)).minusMillis(1);

        RecoveryActionEntity fInside = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(justInside)
                .build();
        RecoveryActionEntity fOutside = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(justOutside)
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(fOutside, fInside));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        // Only fInside is counted, so failureCount = 1 -> not in cooldown
        assertThat(eval.inCooldown()).isFalse();
        assertThat(eval.failureCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("COOLDOWN EDGE CASE 4: Two failures at exact same timestamp trigger cooldown")
    void shouldTriggerCooldownOnTwoFailuresAtExactSameTimestamp() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        Instant sameTime = now.minus(Duration.ofMinutes(25));

        RecoveryActionEntity f1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(sameTime)
                .build();
        RecoveryActionEntity f2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(sameTime)
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(f1, f2));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isTrue();
        assertThat(eval.failureCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("COOLDOWN EDGE CASE 7: Success followed by two new failures triggers cooldown")
    void shouldTriggerCooldownWhenSuccessFollowedByTwoNewFailures() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        RecoveryActionEntity oldSuccess = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.SUCCESS)
                .completedAt(now.minus(Duration.ofMinutes(50)))
                .build();
        RecoveryActionEntity f1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(30)))
                .build();
        RecoveryActionEntity f2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .completedAt(now.minus(Duration.ofMinutes(10)))
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(oldSuccess, f1, f2));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        assertThat(eval.inCooldown()).isTrue();
        assertThat(eval.failureCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("COOLDOWN EDGE CASE 10: Null completedAt falls back to startedAt, plan createdAt, or EPOCH")
    void shouldFallbackTimestampWhenCompletedAtNull() {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .createdAt(now.minus(Duration.ofMinutes(20)))
                .build();

        // f1 uses startedAt
        RecoveryActionEntity f1 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .startedAt(now.minus(Duration.ofMinutes(35)))
                .build();

        // f2 uses plan createdAt
        RecoveryActionEntity f2 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .recoveryPlan(plan)
                .build();

        // f3 has all null timestamps (defaults to EPOCH, outside 1h window)
        RecoveryActionEntity f3 = RecoveryActionEntity.builder()
                .target("auth-service").actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .build();

        when(recoveryActionRepository.findByTargetAndActionType("auth-service", "RESTART_POD"))
                .thenReturn(List.of(f1, f2, f3));

        RecoveryServiceImpl.AntiFlappingEvaluation eval =
                recoveryService.evaluateAntiFlapping("auth-service", "RESTART_POD", now);

        // f1 and f2 are within 1 hour; f3 is EPOCH (excluded) -> count = 2 -> in cooldown
        assertThat(eval.inCooldown()).isTrue();
        assertThat(eval.failureCount()).isEqualTo(2);
    }

    // ==========================================
    // APPROVAL SAFETY & POLICY GUARDRAILS
    // ==========================================

    @Test
    @DisplayName("APPROVAL SAFETY: Rejects approval when action violates active policy prohibition")
    void shouldRejectApprovalWhenPolicyProhibitsAction() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);
        IncidentResponse incident = new IncidentResponse(
                incidentId, resourceId, "Incident", "Desc", IncidentSeverity.HIGH,
                IncidentStatus.DETECTED, 0.9, null, Instant.now(), null, Instant.now(), Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);

        ResourceResponse resource = new ResourceResponse(
                resourceId, "web-service", ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "k8s-node-1", Map.of(), Instant.now(), Instant.now()
        );
        when(resourceService.getResourceById(resourceId)).thenReturn(resource);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder().id(planId).incidentId(incidentId).build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId).recoveryPlan(plan).actionType("RESTART_POD")
                .target("web-service").status(RecoveryActionStatus.PENDING).build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        PolicyResponse prohibitPolicy = new PolicyResponse(
                UUID.randomUUID(), "No Pod Restarts In Prod", ResourceType.SERVICE,
                "*", null, "PROHIBIT_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.SERVICE)).thenReturn(List.of(prohibitPolicy));

        assertThatThrownBy(() -> recoveryService.approveRecoveryAction(incidentId, actionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot approve recovery action")
                .hasMessageContaining("No Pod Restarts In Prod");
    }

    @Test
    @DisplayName("APPROVAL SAFETY: Approves action when permitted by active policy")
    void shouldApproveActionWhenPermittedByPolicy() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);
        IncidentResponse incident = new IncidentResponse(
                incidentId, resourceId, "Incident", "Desc", IncidentSeverity.LOW,
                IncidentStatus.DETECTED, 0.9, null, Instant.now(), null, Instant.now(), Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);

        ResourceResponse resource = new ResourceResponse(
                resourceId, "web-service", ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "k8s-node-1", Map.of(), Instant.now(), Instant.now()
        );
        when(resourceService.getResourceById(resourceId)).thenReturn(resource);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder().id(planId).incidentId(incidentId).build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId).recoveryPlan(plan).actionType("RESTART_POD")
                .target("web-service").status(RecoveryActionStatus.PENDING).build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(recoveryActionRepository.save(any(RecoveryActionEntity.class))).thenAnswer(i -> i.getArgument(0));

        PolicyResponse allowPolicy = new PolicyResponse(
                UUID.randomUUID(), "Allow Restarts", ResourceType.SERVICE,
                "*", null, "ALLOW_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.SERVICE)).thenReturn(List.of(allowPolicy));

        RecoveryActionResponse approved = recoveryService.approveRecoveryAction(incidentId, actionId);

        assertThat(approved.status()).isEqualTo(RecoveryActionStatus.APPROVED);
        assertThat(approved.result()).contains("Approved by human operator for execution");
    }

    // ==========================================
    // METRIC MATCHING PRECISION TESTS
    // ==========================================

    @Test
    @DisplayName("POLICY: Empty candidate metric does not match specific policy")
    void shouldNotMatchSpecificPolicyWhenCandidateMetricIsEmpty() {
        PolicyResponse specificPolicy = new PolicyResponse(
                UUID.randomUUID(), "CPU Specific Policy", ResourceType.POD,
                "cpu_utilization", null, "PROHIBIT_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(specificPolicy));

        RecoveryServiceImpl.PolicyPreFlightEvaluation eval = recoveryService.evaluatePolicyPreFlight(
                ResourceType.POD, "pod-01", "RESTART_POD", "", 90.0
        );

        // Empty metric does not match "cpu_utilization" -> allowed
        assertThat(eval.allowed()).isTrue();
        assertThat(eval.blockReason()).isNull();
    }

    @Test
    @DisplayName("POLICY: Wildcard policy matches even when candidate metric is empty")
    void shouldMatchWildcardPolicyWhenCandidateMetricIsEmpty() {
        PolicyResponse wildcardPolicy = new PolicyResponse(
                UUID.randomUUID(), "Catch-All Prohibit Policy", ResourceType.POD,
                "*", null, "PROHIBIT_RESTART_POD", true, Instant.now(), Instant.now()
        );
        when(policyService.getActivePolicies(ResourceType.POD)).thenReturn(List.of(wildcardPolicy));

        RecoveryServiceImpl.PolicyPreFlightEvaluation eval = recoveryService.evaluatePolicyPreFlight(
                ResourceType.POD, "pod-01", "RESTART_POD", "", 90.0
        );

        // Wildcard policy matches regardless of candidate metric -> blocked
        assertThat(eval.allowed()).isFalse();
        assertThat(eval.blockReason()).contains("Catch-All Prohibit Policy");
    }
}
