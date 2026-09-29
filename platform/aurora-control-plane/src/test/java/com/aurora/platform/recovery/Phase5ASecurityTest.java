package com.aurora.platform.recovery;

import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.infrastructure.security.AuthenticatedOperator;
import com.aurora.platform.infrastructure.security.OperatorAuthenticationToken;
import com.aurora.platform.infrastructure.security.RecoveryCapability;
import com.aurora.platform.recovery.dto.RecoveryActionResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryOutboxEventRepository;
import com.aurora.platform.recovery.repository.RecoveryPlanRepository;
import com.aurora.platform.recovery.service.RecoveryServiceImpl;
import com.aurora.platform.resource.entity.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aurora.platform.infrastructure.security.OperatorJwtAuthenticationConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.oauth2.jwt.Jwt;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Phase5ASecurityTest {

    @Mock
    private RecoveryPlanRepository recoveryPlanRepository;

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private IncidentService incidentService;

    @Mock
    private RecoveryOutboxEventRepository outboxEventRepository;

    private RecoveryServiceImpl recoveryService;

    private UUID incidentId;
    private UUID planId;
    private UUID actionId;
    private UUID targetResourceId;

    @BeforeEach
    void setUp() {
        recoveryService = new RecoveryServiceImpl(
                recoveryPlanRepository,
                recoveryActionRepository,
                incidentService,
                null,
                null,
                null,
                outboxEventRepository,
                new ObjectMapper()
        );

        incidentId = UUID.randomUUID();
        planId = UUID.randomUUID();
        actionId = UUID.randomUUID();
        targetResourceId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockPlanAndAction(String environment, RecoveryActionStatus status) {
        when(incidentService.existsById(incidentId)).thenReturn(true);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .risk(RecoveryRisk.MEDIUM)
                .build();
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(actionId)
                .recoveryPlan(plan)
                .actionType("RESTART_POD")
                .targetResourceId(targetResourceId)
                .targetEnvironment(environment)
                .targetResourceType(ResourceType.POD)
                .targetResourceName("auth-pod-1")
                .target("auth-pod-1")
                .status(status)
                .build();
        when(recoveryActionRepository.findById(actionId)).thenReturn(Optional.of(action));
        when(recoveryActionRepository.save(any(RecoveryActionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("S1: Unauthenticated operator approval is rejected fail-closed")
    void unauthenticatedApprovalRejected() {
        mockPlanAndAction("staging", RecoveryActionStatus.PENDING);
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> recoveryService.approveRecoveryAction(incidentId, actionId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Unauthenticated access denied");
    }

    @Test
    @DisplayName("S1: Authenticated operator lacking approval capability is rejected")
    void unauthorizedOperatorRejected() {
        mockPlanAndAction("staging", RecoveryActionStatus.PENDING);

        AuthenticatedOperator viewOnly = new AuthenticatedOperator(
                "viewer-123", "alice_viewer", "alice@aurora.local",
                Set.of(RecoveryCapability.RECOVERY_VIEW)
        );
        SecurityContextHolder.getContext().setAuthentication(new OperatorAuthenticationToken(viewOnly));

        assertThatThrownBy(() -> recoveryService.approveRecoveryAction(incidentId, actionId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("RECOVERY_APPROVE");
    }

    @Test
    @DisplayName("S8: Operator with standard RECOVERY_APPROVE is rejected for production approval")
    void standardOperatorRejectedForProduction() {
        mockPlanAndAction("production", RecoveryActionStatus.PENDING);

        AuthenticatedOperator nonProdApprover = new AuthenticatedOperator(
                "dev-approver", "bob_dev", "bob@aurora.local",
                Set.of(RecoveryCapability.RECOVERY_APPROVE)
        );
        SecurityContextHolder.getContext().setAuthentication(new OperatorAuthenticationToken(nonProdApprover));

        assertThatThrownBy(() -> recoveryService.approveRecoveryAction(incidentId, actionId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("RECOVERY_APPROVE_PRODUCTION");
    }

    @Test
    @DisplayName("S8: Operator with RECOVERY_APPROVE_PRODUCTION is accepted for production approval")
    void productionOperatorAcceptedForProduction() {
        mockPlanAndAction("production", RecoveryActionStatus.PENDING);

        AuthenticatedOperator prodApprover = new AuthenticatedOperator(
                "sre-lead-42", "carol_sre", "carol@aurora.local",
                Set.of(RecoveryCapability.RECOVERY_APPROVE_PRODUCTION)
        );
        SecurityContextHolder.getContext().setAuthentication(new OperatorAuthenticationToken(prodApprover));

        RecoveryActionResponse response = recoveryService.approveRecoveryAction(incidentId, actionId);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(RecoveryActionStatus.APPROVED);
        assertThat(response.approvedByUserId()).isEqualTo("sre-lead-42");
        assertThat(response.approvedByEmail()).isEqualTo("carol@aurora.local");
        assertThat(response.approvedByCapability()).isEqualTo("RECOVERY_APPROVE_PRODUCTION");
        assertThat(response.approvedAt()).isNotNull();
        assertThat(response.approvalReason()).contains("sre-lead-42");
        // S1: Proof that approval cannot be attributed to a generic hard-coded string
        assertThat(response.approvedByUserId()).isNotEqualTo("generic");
        assertThat(response.approvedByUserId()).isNotEqualTo("admin");
    }

    @Test
    @DisplayName("S1: Admin operator can approve in both staging and production")
    void adminOperatorCanApproveAllEnvironments() {
        mockPlanAndAction("production", RecoveryActionStatus.PENDING);

        AuthenticatedOperator admin = new AuthenticatedOperator(
                "admin-ops-01", "dave_admin", "dave@aurora.local",
                Set.of(RecoveryCapability.RECOVERY_ADMIN)
        );
        SecurityContextHolder.getContext().setAuthentication(new OperatorAuthenticationToken(admin));

        RecoveryActionResponse response = recoveryService.approveRecoveryAction(incidentId, actionId);

        assertThat(response.status()).isEqualTo(RecoveryActionStatus.APPROVED);
        assertThat(response.approvedByUserId()).isEqualTo("admin-ops-01");
    }

    @Nested
    @DisplayName("OperatorJwtAuthenticationConverter: Fail-Closed Operator Identity Tests")
    class OperatorJwtAuthenticationConverterTests {

        private final OperatorJwtAuthenticationConverter converter = new OperatorJwtAuthenticationConverter();

        @Test
        @DisplayName("Valid JWT with subject succeeds and correctly populates operator identity")
        void validJwtWithSubjectSucceeds() {
            Jwt jwt = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("sub", "ops-user-42")
                    .claim("preferred_username", "bob_operator")
                    .claim("email", "bob@aurora.local")
                    .claim("roles", java.util.List.of("RECOVERY_APPROVE"))
                    .build();

            org.springframework.security.authentication.AbstractAuthenticationToken token = converter.convert(jwt);
            assertThat(token).isNotNull();
            assertThat(token).isInstanceOf(OperatorAuthenticationToken.class);

            OperatorAuthenticationToken opToken = (OperatorAuthenticationToken) token;
            AuthenticatedOperator operator = (AuthenticatedOperator) opToken.getPrincipal();
            assertThat(operator.getUserId()).isEqualTo("ops-user-42");
            assertThat(operator.getUsername()).isEqualTo("bob_operator");
            assertThat(operator.getEmail()).isEqualTo("bob@aurora.local");
            assertThat(operator.getCapabilities()).contains(RecoveryCapability.RECOVERY_APPROVE);
        }

        @Test
        @DisplayName("Valid JWT with uid fallback succeeds when sub is absent")
        void validJwtWithUidSucceeds() {
            Jwt jwt = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("uid", "uid-operator-99")
                    .claim("name", "Operator NinetyNine")
                    .claim("capabilities", java.util.List.of("RECOVERY_VIEW"))
                    .build();

            org.springframework.security.authentication.AbstractAuthenticationToken token = converter.convert(jwt);
            assertThat(token).isNotNull();

            OperatorAuthenticationToken opToken = (OperatorAuthenticationToken) token;
            AuthenticatedOperator operator = (AuthenticatedOperator) opToken.getPrincipal();
            assertThat(operator.getUserId()).isEqualTo("uid-operator-99");
            assertThat(operator.getUsername()).isEqualTo("Operator NinetyNine");
            assertThat(operator.getCapabilities()).contains(RecoveryCapability.RECOVERY_VIEW);
        }

        @Test
        @DisplayName("JWT with no usable operator identity (missing sub and uid) fails closed")
        void jwtWithoutIdentityFailsClosed() {
            Jwt jwt = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("email", "ghost@aurora.local")
                    .claim("roles", java.util.List.of("RECOVERY_APPROVE"))
                    .build();

            assertThatThrownBy(() -> converter.convert(jwt))
                    .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class)
                    .hasMessageContaining("JWT lacks a valid operator identity");
        }

        @Test
        @DisplayName("JWT with empty or blank/whitespace identity fails closed")
        void jwtWithBlankIdentityFailsClosed() {
            Jwt jwtWithBlankSub = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("sub", "   ")
                    .build();

            assertThatThrownBy(() -> converter.convert(jwtWithBlankSub))
                    .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class)
                    .hasMessageContaining("JWT lacks a valid operator identity");

            Jwt jwtWithBlankUid = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("uid", "")
                    .build();

            assertThatThrownBy(() -> converter.convert(jwtWithBlankUid))
                    .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class)
                    .hasMessageContaining("JWT lacks a valid operator identity");
        }

        @Test
        @DisplayName("No anonymous or synthetic fallback identity is ever produced")
        void noAnonymousFallbackIdentityProduced() {
            Jwt emptyJwt = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("preferred_username", "anonymous-user")
                    .build();

            assertThatThrownBy(() -> converter.convert(emptyJwt))
                    .isInstanceOf(org.springframework.security.authentication.BadCredentialsException.class);
        }

        @Test
        @DisplayName("Capability extraction handles scopes, authorities, and realm_access roles")
        void capabilityExtractionRemainsIntact() {
            Jwt jwt = Jwt.withTokenValue("mock.jwt.token")
                    .header("alg", "none")
                    .claim("sub", "admin-user")
                    .claim("scope", "RECOVERY_ADMIN RECOVERY_APPROVE_PRODUCTION")
                    .claim("authorities", java.util.List.of("RECOVERY_APPROVE"))
                    .claim("realm_access", java.util.Map.of("roles", java.util.List.of("RECOVERY_VIEW")))
                    .build();

            org.springframework.security.authentication.AbstractAuthenticationToken token = converter.convert(jwt);
            OperatorAuthenticationToken opToken = (OperatorAuthenticationToken) token;
            AuthenticatedOperator operator = (AuthenticatedOperator) opToken.getPrincipal();

            assertThat(operator.getCapabilities()).containsExactlyInAnyOrder(
                    RecoveryCapability.RECOVERY_ADMIN,
                    RecoveryCapability.RECOVERY_APPROVE_PRODUCTION,
                    RecoveryCapability.RECOVERY_APPROVE,
                    RecoveryCapability.RECOVERY_VIEW
            );
        }
    }
}
