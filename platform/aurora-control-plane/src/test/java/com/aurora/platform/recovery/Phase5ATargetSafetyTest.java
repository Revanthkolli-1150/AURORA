package com.aurora.platform.recovery;

import com.aurora.platform.infrastructure.security.AuthenticatedOperator;
import com.aurora.platform.infrastructure.security.OperatorAuthenticationToken;
import com.aurora.platform.infrastructure.security.RecoveryCapability;
import com.aurora.platform.recovery.application.gate.ExecutionAuthorizationGate;
import com.aurora.platform.recovery.application.gate.GateEvaluationResult;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.repository.ExecutionAttemptRepository;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.service.RecoveryServiceImpl;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Phase5ATargetSafetyTest {

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private ExecutionAttemptRepository executionAttemptRepository;

    @Mock
    private ResourceService resourceService;

    @Mock
    private RecoveryServiceImpl recoveryService;

    private ExecutionAuthorizationGate gate;
    private Instant now;

    @BeforeEach
    void setUp() {
        gate = new ExecutionAuthorizationGate(
                recoveryActionRepository,
                executionAttemptRepository,
                resourceService,
                recoveryService
        );

        now = Instant.parse("2026-09-30T10:00:00Z");
        gate.setClock(Clock.fixed(now, ZoneId.of("UTC")));

        AuthenticatedOperator operator = new AuthenticatedOperator(
                "sre-lead", "sre-lead", "sre@aurora.local",
                Set.of(RecoveryCapability.RECOVERY_APPROVE, RecoveryCapability.RECOVERY_APPROVE_PRODUCTION)
        );
        SecurityContextHolder.getContext().setAuthentication(new OperatorAuthenticationToken(operator));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("S7 & Anti-Flapping: Staging and production resources with identical names are strictly isolated by UUID")
    void crossEnvironmentIsolationByUuid() {
        UUID stagingResourceId = UUID.randomUUID();
        UUID prodResourceId = UUID.randomUUID();
        String commonName = "payment-service";

        ResourceResponse stagingRes = new ResourceResponse(
                stagingResourceId, commonName, ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "staging", "host-staging", Map.of(), now, now
        );
        ResourceResponse prodRes = new ResourceResponse(
                prodResourceId, commonName, ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "host-prod", Map.of(), now, now
        );

        when(resourceService.getResourceById(stagingResourceId)).thenReturn(stagingRes);
        when(resourceService.getResourceById(prodResourceId)).thenReturn(prodRes);

        // Suppose staging has 3 failed actions recorded in the cooldown window
        RecoveryActionEntity stagingFail1 = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .targetResourceId(stagingResourceId)
                .actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .startedAt(now.minusSeconds(600))
                .completedAt(now.minusSeconds(500))
                .build();
        RecoveryActionEntity stagingFail2 = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .targetResourceId(stagingResourceId)
                .actionType("RESTART_POD")
                .status(RecoveryActionStatus.FAILED)
                .startedAt(now.minusSeconds(400))
                .completedAt(now.minusSeconds(300))
                .build();

        when(recoveryActionRepository.findByTargetResourceIdAndActionType(stagingResourceId, "RESTART_POD"))
                .thenReturn(List.of(stagingFail1, stagingFail2));
        // Production has zero failures for this resource
        when(recoveryActionRepository.findByTargetResourceIdAndActionType(prodResourceId, "RESTART_POD"))
                .thenReturn(List.of());

        RecoveryServiceImpl realRecoveryService = new RecoveryServiceImpl(
                null, recoveryActionRepository, null, null, resourceService, null
        );
        realRecoveryService.setClock(Clock.fixed(now, ZoneId.of("UTC")));

        // Evaluate anti-flapping for staging
        RecoveryServiceImpl.AntiFlappingEvaluation stagingEval =
                realRecoveryService.evaluateAntiFlapping(stagingResourceId, "RESTART_POD", now);
        assertThat(stagingEval.inCooldown()).isTrue();
        assertThat(stagingEval.failureCount()).isEqualTo(2);

        // Evaluate anti-flapping for production
        RecoveryServiceImpl.AntiFlappingEvaluation prodEval =
                realRecoveryService.evaluateAntiFlapping(prodResourceId, "RESTART_POD", now);
        assertThat(prodEval.inCooldown()).isFalse();
        assertThat(prodEval.failureCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("S17: Target Immutability - Gate rejects execution if target environment diverges from canonical resource")
    void targetImmutabilityRejectsDivergentEnvironment() {
        UUID actionId = UUID.randomUUID();
        UUID canonicalResourceId = UUID.randomUUID();

        // The canonical resource in database is staging
        ResourceResponse canonicalRes = new ResourceResponse(
                canonicalResourceId, "auth-service", ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "staging", "host-01", Map.of(), now, now
        );
        when(resourceService.getResourceById(canonicalResourceId)).thenReturn(canonicalRes);

        // But the action was tampered or diverged to claim it is "production"
        RecoveryActionEntity tamperedAction = RecoveryActionEntity.builder()
                .id(actionId)
                .actionType("RESTART_POD")
                .targetResourceId(canonicalResourceId)
                .targetEnvironment("production") // Divergent!
                .targetResourceType(ResourceType.SERVICE)
                .targetResourceName("auth-service")
                .status(RecoveryActionStatus.APPROVED)
                .approvedByUserId("sre-lead")
                .approvedByCapability("RECOVERY_APPROVE_PRODUCTION")
                .build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(tamperedAction));

        GateEvaluationResult result = gate.evaluate(actionId, "test-idemp-key-1");

        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S17 Violation");
    }

    @Test
    @DisplayName("S7: Canonical Target Binding - Gate rejects execution when canonical resource cannot be found")
    void canonicalTargetBindingRejectsUnresolvedTarget() {
        UUID actionId = UUID.randomUUID();
        UUID missingResourceId = UUID.randomUUID();

        when(resourceService.getResourceById(missingResourceId)).thenReturn(null);

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId)
                .actionType("RESTART_POD")
                .targetResourceId(missingResourceId)
                .targetEnvironment("staging")
                .targetResourceType(ResourceType.SERVICE)
                .targetResourceName("ghost-service")
                .status(RecoveryActionStatus.APPROVED)
                .approvedByUserId("sre-lead")
                .approvedByCapability("RECOVERY_APPROVE")
                .build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));

        GateEvaluationResult result = gate.evaluate(actionId, "test-idemp-key-2");

        assertThat(result.allowed()).isFalse();
        assertThat(result.rejectionReason()).contains("S7 Violation");
    }
}
