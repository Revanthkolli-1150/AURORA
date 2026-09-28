package com.aurora.platform.intelligence.historical.controller;

import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.intelligence.historical.dto.SimilarityBreakdownResponse;
import com.aurora.platform.intelligence.historical.service.HistoricalIncidentService;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HistoricalIncidentController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class HistoricalIncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HistoricalIncidentService historicalIncidentService;

    @Test
    @DisplayName("1. GET /api/v1/incidents/{id}/similar - Returns 200 OK with matches")
    void shouldReturnSimilarIncidents() throws Exception {
        UUID targetId = UUID.randomUUID();
        UUID histId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();

        SimilarityBreakdownResponse breakdown = new SimilarityBreakdownResponse(
                1.00, 1.00, 1.00, 1.00,
                Set.of("db_connections"), Set.of("UPSTREAM:DATABASE")
        );

        SimilarIncidentResponse match = new SimilarIncidentResponse(
                histId,
                resId,
                "postgres-db-01",
                ResourceType.DATABASE,
                IncidentSeverity.CRITICAL,
                0.95,
                breakdown,
                "Connection pool leak",
                Instant.now().minusSeconds(500),
                300L,
                "Matched 95% with previous database incident"
        );

        when(historicalIncidentService.findSimilarIncidents(targetId, null, null))
                .thenReturn(List.of(match));

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].historicalIncidentId").value(histId.toString()))
                .andExpect(jsonPath("$[0].resourceName").value("postgres-db-01"))
                .andExpect(jsonPath("$[0].resourceType").value("DATABASE"))
                .andExpect(jsonPath("$[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$[0].similarityScore").value(0.95))
                .andExpect(jsonPath("$[0].primaryRcaCause").value("Connection pool leak"))
                .andExpect(jsonPath("$[0].resolutionDurationSeconds").value(300))
                .andExpect(jsonPath("$[0].breakdown.metricScore").value(1.00))
                .andExpect(jsonPath("$[0].breakdown.matchedMetrics[0]").value("db_connections"));
    }

    @Test
    @DisplayName("2. GET /api/v1/incidents/{id}/similar - Returns 200 OK with empty list when no matches")
    void shouldReturnEmptyListWhenNoMatches() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(historicalIncidentService.findSimilarIncidents(targetId, 5, 0.50))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId)
                        .param("limit", "5")
                        .param("minScore", "0.50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("3. GET /api/v1/incidents/{id}/similar - Returns 404 when target incident does not exist")
    void shouldReturn404WhenTargetNotFound() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(historicalIncidentService.findSimilarIncidents(targetId, null, null))
                .thenThrow(new ResourceNotFoundException("Incident with ID '" + targetId + "' not found"));

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Incident with ID '" + targetId + "' not found"));
    }

    @Test
    @DisplayName("4. GET /api/v1/incidents/{id}/similar - Returns 400 when limit is less than 1")
    void shouldReturn400WhenLimitLessThan1() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(historicalIncidentService.findSimilarIncidents(targetId, 0, null))
                .thenThrow(new IllegalArgumentException("limit must be between 1 and 20 (requested: 0)"));

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId).param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 20 (requested: 0)"));
    }

    @Test
    @DisplayName("5. GET /api/v1/incidents/{id}/similar - Returns 400 when minScore is out of range")
    void shouldReturn400WhenMinScoreInvalid() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(historicalIncidentService.findSimilarIncidents(targetId, null, 1.5))
                .thenThrow(new IllegalArgumentException("minScore must be between 0.00 and 1.00 (requested: 1.5)"));

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId).param("minScore", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("6. GET /api/v1/incidents/{id}/similar - Passes default parameters when omitted")
    void shouldPassDefaultParametersWhenOmitted() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(historicalIncidentService.findSimilarIncidents(targetId, null, null))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId))
                .andExpect(status().isOk());

        verify(historicalIncidentService).findSimilarIncidents(targetId, null, null);
    }

    @Test
    @DisplayName("7. GET /api/v1/incidents/{id}/similar - Returns 400 when limit exceeds maximum of 20")
    void shouldReturn400WhenLimitExceedsMax() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(historicalIncidentService.findSimilarIncidents(targetId, 25, null))
                .thenThrow(new IllegalArgumentException("limit must be between 1 and 20 (requested: 25)"));

        mockMvc.perform(get("/api/v1/incidents/{id}/similar", targetId).param("limit", "25"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 20 (requested: 25)"));
    }
}
