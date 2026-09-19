package com.aurora.platform.recovery.controller;

import com.aurora.platform.common.config.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
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
}
