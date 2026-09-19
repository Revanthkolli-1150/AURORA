package com.aurora.platform.incidents.controller;

import com.aurora.platform.common.config.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incidents.dto.IncidentResponse;
import com.aurora.platform.incidents.entity.IncidentSeverity;
import com.aurora.platform.incidents.entity.IncidentStatus;
import com.aurora.platform.incidents.service.IncidentService;
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

@WebMvcTest(IncidentController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
}
