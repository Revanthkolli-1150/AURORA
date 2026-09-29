package com.aurora.platform.recovery.controller;

import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.recovery.dto.RecoveryActionResponse;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.service.RecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecoveryController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class RecoveryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecoveryService recoveryService;

    @Test
    @DisplayName("GET /api/v1/incidents/{incidentId}/recovery-plan - Should return 200 OK")
    void shouldReturnRecoveryPlan() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        RecoveryActionResponse action = new RecoveryActionResponse(
                actionId,
                planId,
                "FLUSH_REDIS_BUFFER",
                "redis-cache-node",
                RecoveryActionStatus.PENDING,
                null,
                null,
                null
        );

        RecoveryPlanResponse plan = new RecoveryPlanResponse(
                planId,
                incidentId,
                "Clear memory buffer and restart service worker",
                0.88,
                RecoveryRisk.MEDIUM,
                true,
                List.of(action),
                Instant.now()
        );

        when(recoveryService.getRecoveryPlanByIncidentId(incidentId)).thenReturn(plan);

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/recovery-plan", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(planId.toString()))
                .andExpect(jsonPath("$.incidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$.risk").value("MEDIUM"))
                .andExpect(jsonPath("$.actions", hasSize(1)))
                .andExpect(jsonPath("$.actions[0].actionType").value("FLUSH_REDIS_BUFFER"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{incidentId}/recovery-plan - Should return 404 when plan missing")
    void shouldReturn404WhenPlanNotFound() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(recoveryService.getRecoveryPlanByIncidentId(incidentId))
                .thenThrow(new ResourceNotFoundException("Recovery plan for incident ID '" + incidentId + "' not found"));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/recovery-plan", incidentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/recovery-plan - Should return 201 Created on plan generation")
    void shouldGenerateRecoveryPlan() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        RecoveryActionResponse action = new RecoveryActionResponse(
                actionId,
                planId,
                "SCALE_OUT",
                "payment-service",
                RecoveryActionStatus.PENDING,
                "Increase replicas",
                null,
                null
        );

        RecoveryPlanResponse plan = new RecoveryPlanResponse(
                planId,
                incidentId,
                "Scale out degraded payment service",
                0.91,
                RecoveryRisk.LOW,
                false,
                List.of(action),
                Instant.now()
        );

        when(recoveryService.generateRecoveryPlan(incidentId)).thenReturn(plan);

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/recovery-plan", incidentId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(planId.toString()))
                .andExpect(jsonPath("$.actions", hasSize(1)))
                .andExpect(jsonPath("$.actions[0].actionType").value("SCALE_OUT"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve - Should return 200 OK")
    void shouldApproveRecoveryAction() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        RecoveryActionResponse approvedAction = new RecoveryActionResponse(
                actionId,
                planId,
                "RESTART_POD",
                "auth-service",
                RecoveryActionStatus.APPROVED,
                "Approved by human operator for execution",
                null,
                null
        );

        when(recoveryService.approveRecoveryAction(incidentId, actionId)).thenReturn(approvedAction);

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve",
                        incidentId, actionId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("test-operator").claim("capabilities", java.util.List.of("RECOVERY_APPROVE")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(actionId.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.result").value("Approved by human operator for execution"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve - Should return 404 when action missing")
    void shouldReturn404WhenActionMissing() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        when(recoveryService.approveRecoveryAction(incidentId, actionId))
                .thenThrow(new ResourceNotFoundException("Recovery action with ID '" + actionId + "' not found"));

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve",
                        incidentId, actionId)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                                .jwt(jwt -> jwt.subject("test-operator").claim("capabilities", java.util.List.of("RECOVERY_APPROVE")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve - Should return 401 when unauthenticated")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID actionId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve",
                        incidentId, actionId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }
}
