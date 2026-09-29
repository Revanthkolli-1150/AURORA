package com.aurora.platform.policy.controller;

import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.policy.dto.CreatePolicyRequest;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.service.PolicyService;
import com.aurora.platform.resource.entity.ResourceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PolicyController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class PolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PolicyService policyService;

    @Test
    @DisplayName("GET /api/v1/policies - Should return 200 OK with list of policies")
    void shouldReturnPoliciesList() throws Exception {
        UUID policyId = UUID.randomUUID();
        PolicyResponse response = new PolicyResponse(
                policyId,
                "High Error Rate",
                ResourceType.SERVICE,
                "error_rate",
                5.0,
                "CIRCUIT_BREAKER_ENABLE",
                true,
                Instant.now(),
                Instant.now()
        );

        when(policyService.getAllPolicies(null, null)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(policyId.toString()))
                .andExpect(jsonPath("$[0].name").value("High Error Rate"));
    }

    @Test
    @DisplayName("GET /api/v1/policies/{id} - Should return 200 OK when policy exists")
    void shouldReturnPolicyById() throws Exception {
        UUID policyId = UUID.randomUUID();
        PolicyResponse response = new PolicyResponse(
                policyId,
                "High CPU Usage",
                ResourceType.POD,
                "cpu_usage",
                90.0,
                "SCALE_OUT",
                true,
                Instant.now(),
                Instant.now()
        );

        when(policyService.getPolicyById(policyId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/policies/{id}", policyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(policyId.toString()))
                .andExpect(jsonPath("$.metricName").value("cpu_usage"));
    }

    @Test
    @DisplayName("GET /api/v1/policies/{id} - Should return 404 when missing")
    void shouldReturn404WhenMissing() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(policyService.getPolicyById(missingId))
                .thenThrow(new ResourceNotFoundException("Policy with ID '" + missingId + "' not found"));

        mockMvc.perform(get("/api/v1/policies/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("POST /api/v1/policies - Should return 201 Created with Location header")
    void shouldCreatePolicy() throws Exception {
        UUID policyId = UUID.randomUUID();
        CreatePolicyRequest request = new CreatePolicyRequest(
                "Thread Pool Saturation",
                ResourceType.SERVICE,
                "active_threads",
                200.0,
                "RESTART_POD",
                true
        );

        PolicyResponse response = new PolicyResponse(
                policyId,
                request.name(),
                request.targetResourceType(),
                request.metricName(),
                request.threshold(),
                request.action(),
                true,
                Instant.now(),
                Instant.now()
        );

        when(policyService.createPolicy(any(CreatePolicyRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").value(policyId.toString()))
                .andExpect(jsonPath("$.name").value("Thread Pool Saturation"));
    }

    @Test
    @DisplayName("POST /api/v1/policies - Should return 400 Bad Request on invalid input")
    void shouldReturn400OnInvalidInput() throws Exception {
        CreatePolicyRequest invalidRequest = new CreatePolicyRequest(
                "", // Blank name
                null,
                "",
                null,
                "",
                null
        );

        mockMvc.perform(post("/api/v1/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("PATCH /api/v1/policies/{id}/status - Should return 200 OK")
    void shouldUpdateStatus() throws Exception {
        UUID policyId = UUID.randomUUID();
        PolicyResponse updated = new PolicyResponse(
                policyId,
                "High Error Rate",
                ResourceType.SERVICE,
                "error_rate",
                5.0,
                "CIRCUIT_BREAKER_ENABLE",
                false,
                Instant.now(),
                Instant.now()
        );

        when(policyService.updatePolicyStatus(eq(policyId), eq(false))).thenReturn(updated);

        mockMvc.perform(patch("/api/v1/policies/{id}/status", policyId)
                        .param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
