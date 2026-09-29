package com.aurora.platform.incident.controller;

import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IncidentService incidentService;

    @Test
    @DisplayName("GET /api/v1/incidents - Should return 200 OK with incidents list")
    void shouldReturnIncidentsList() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        IncidentResponse response = new IncidentResponse(
                incidentId,
                resourceId,
                "API Gateway 5xx Spike",
                "Error rate exceeded 5% threshold",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                0.91,
                "Upstream timeout",
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );

        when(incidentService.getAllIncidents(null, null, null)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(incidentId.toString()))
                .andExpect(jsonPath("$[0].title").value("API Gateway 5xx Spike"))
                .andExpect(jsonPath("$[0].severity").value("HIGH"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id} - Should return 200 OK when incident exists")
    void shouldReturnIncidentById() throws Exception {
        UUID incidentId = UUID.randomUUID();
        IncidentResponse response = new IncidentResponse(
                incidentId,
                UUID.randomUUID(),
                "Disk space critical",
                "Partition /var/log at 98%",
                IncidentSeverity.CRITICAL,
                IncidentStatus.INVESTIGATING,
                0.99,
                "Log rotation failed",
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );

        when(incidentService.getIncidentById(incidentId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/incidents/{id}", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.severity").value("CRITICAL"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id} - Should return 404 when incident missing")
    void shouldReturn404WhenMissing() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(incidentService.getIncidentById(missingId))
                .thenThrow(new ResourceNotFoundException("Incident with ID '" + missingId + "' not found"));

        mockMvc.perform(get("/api/v1/incidents/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/evidence - Should return 200 OK with evidence list")
    void shouldReturnEvidenceList() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();

        com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse evidence =
                new com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse(
                        evidenceId,
                        incidentId,
                        resourceId,
                        "cpu_usage",
                        92.5,
                        1.0,
                        4.5,
                        "Z_SCORE",
                        Instant.now(),
                        Instant.now()
                );

        when(incidentService.getEvidenceByIncidentId(incidentId)).thenReturn(List.of(evidence));

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(evidenceId.toString()))
                .andExpect(jsonPath("$[0].incidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$[0].metricName").value("cpu_usage"))
                .andExpect(jsonPath("$[0].observedValue").value(92.5))
                .andExpect(jsonPath("$[0].anomalyScore").value(1.0))
                .andExpect(jsonPath("$[0].zScore").value(4.5))
                .andExpect(jsonPath("$[0].detectionMethod").value("Z_SCORE"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/evidence - Should return 404 when incident missing")
    void shouldReturn404WhenGettingEvidenceForMissingIncident() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(incidentService.getEvidenceByIncidentId(missingId))
                .thenThrow(new ResourceNotFoundException("Incident with ID '" + missingId + "' not found"));

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("PATCH /api/v1/incidents/{id}/status - Should return 200 OK on legal transition")
    void shouldReturn200OnLegalStatusTransition() throws Exception {
        UUID incidentId = UUID.randomUUID();
        IncidentResponse updated = new IncidentResponse(
                incidentId,
                UUID.randomUUID(),
                "API Gateway 5xx Spike",
                "Under investigation",
                IncidentSeverity.HIGH,
                IncidentStatus.INVESTIGATING,
                0.91,
                null,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );

        when(incidentService.updateIncidentStatus(incidentId, IncidentStatus.INVESTIGATING))
                .thenReturn(updated);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/incidents/{id}/status", incidentId)
                        .param("status", "INVESTIGATING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));
    }

    @Test
    @DisplayName("PATCH /api/v1/incidents/{id}/status - Should return 409 Conflict on illegal state transition")
    void shouldReturn409OnIllegalStatusTransition() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(incidentService.updateIncidentStatus(incidentId, IncidentStatus.RESOLVED))
                .thenThrow(new IllegalStateException("Illegal incident lifecycle transition from DETECTED to RESOLVED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/incidents/{id}/status", incidentId)
                        .param("status", "RESOLVED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("ILLEGAL_STATE"))
                .andExpect(jsonPath("$.message").value("Illegal incident lifecycle transition from DETECTED to RESOLVED"));
    }

    @Test
    @DisplayName("PATCH /api/v1/incidents/{id}/status - Should return 400 Bad Request on invalid status enum parameter")
    void shouldReturn400OnInvalidStatusEnum() throws Exception {
        UUID incidentId = UUID.randomUUID();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/incidents/{id}/status", incidentId)
                        .param("status", "NOT_A_VALID_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("PATCH /api/v1/incidents/{id}/status - Should return 404 Not Found on missing incident")
    void shouldReturn404OnMissingIncidentStatusUpdate() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(incidentService.updateIncidentStatus(missingId, IncidentStatus.INVESTIGATING))
                .thenThrow(new ResourceNotFoundException("Incident with ID '" + missingId + "' not found"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/v1/incidents/{id}/status", missingId)
                        .param("status", "INVESTIGATING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    // ==========================================
    // MANUAL INCIDENT CREATION TESTS (PHASE 4B)
    // ==========================================

    @Test
    @DisplayName("POST /api/v1/incidents - Should create incident and return 201 Created with Location header")
    void shouldCreateIncidentSuccessfully() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        CreateIncidentRequest request = new CreateIncidentRequest(
                resourceId,
                "Manual Alert: Database Degradation",
                "Operator noticed connection pool depletion manually",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                0.90,
                "Manual observation",
                Instant.now()
        );

        IncidentResponse response = new IncidentResponse(
                incidentId,
                resourceId,
                request.title(),
                request.description(),
                request.severity(),
                request.status(),
                request.confidence(),
                request.rootCause(),
                request.detectedAt(),
                null,
                Instant.now(),
                Instant.now()
        );

        when(incidentService.createIncident(any(CreateIncidentRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/incidents/" + incidentId)))
                .andExpect(jsonPath("$.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.title").value("Manual Alert: Database Degradation"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.status").value("DETECTED"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents - Should return 400 Bad Request on validation failure (blank title)")
    void shouldReturn400OnValidationFailureBlankTitle() throws Exception {
        UUID resourceId = UUID.randomUUID();
        CreateIncidentRequest invalidRequest = new CreateIncidentRequest(
                resourceId,
                "", // Blank title
                "Some description",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents - Should return 400 Bad Request on validation failure (missing resourceId)")
    void shouldReturn400OnValidationFailureMissingResource() throws Exception {
        CreateIncidentRequest invalidRequest = new CreateIncidentRequest(
                null, // Missing resource ID
                "Title",
                "Description",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                null,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /api/v1/incidents - Should return 404 Not Found when referenced resource does not exist")
    void shouldReturn404WhenReferencedResourceMissing() throws Exception {
        UUID nonExistentResourceId = UUID.randomUUID();
        CreateIncidentRequest request = new CreateIncidentRequest(
                nonExistentResourceId,
                "Title",
                "Description",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                null,
                null,
                null
        );

        when(incidentService.createIncident(any(CreateIncidentRequest.class)))
                .thenThrow(new ResourceNotFoundException("Resource with ID '" + nonExistentResourceId + "' not found"));

        mockMvc.perform(post("/api/v1/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString(nonExistentResourceId.toString())));
    }
}
