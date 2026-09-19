package com.aurora.platform.telemetry.controller;

import com.aurora.platform.common.config.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.service.TelemetryService;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelemetryController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class TelemetryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TelemetryService telemetryService;

    @Test
    @DisplayName("POST /api/v1/telemetry - Should ingest telemetry and return 201 Created")
    void shouldIngestTelemetryAndReturn201() throws Exception {
        UUID resourceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        IngestTelemetryRequest request = new IngestTelemetryRequest(
                resourceId,
                now,
                TelemetryType.METRIC,
                "jvm.memory.used",
                104857600.0,
                "bytes",
                Map.of("area", "heap")
        );

        TelemetryEventResponse response = new TelemetryEventResponse(
                eventId,
                resourceId,
                now,
                TelemetryType.METRIC,
                "jvm.memory.used",
                104857600.0,
                "bytes",
                Map.of("area", "heap")
        );

        when(telemetryService.ingestTelemetry(any(IngestTelemetryRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(eventId.toString()))
                .andExpect(jsonPath("$.resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$.metricName").value("jvm.memory.used"))
                .andExpect(jsonPath("$.value").value(104857600.0));
    }

    @Test
    @DisplayName("POST /api/v1/telemetry - Should return 400 on invalid input")
    void shouldReturn400OnInvalidInput() throws Exception {
        IngestTelemetryRequest invalid = new IngestTelemetryRequest(
                null,
                null,
                null,
                "",
                null,
                "",
                null
        );

        mockMvc.perform(post("/api/v1/telemetry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /api/v1/telemetry/resource/{resourceId} - Should return 200 OK")
    void shouldReturnTelemetryForResource() throws Exception {
        UUID resourceId = UUID.randomUUID();
        TelemetryEventResponse event = new TelemetryEventResponse(
                UUID.randomUUID(),
                resourceId,
                Instant.now(),
                TelemetryType.METRIC,
                "cpu.usage",
                45.0,
                "percent",
                Collections.emptyMap()
        );

        when(telemetryService.getTelemetryByResourceId(resourceId)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/v1/telemetry/resource/{resourceId}", resourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].metricName").value("cpu.usage"));
    }

    @Test
    @DisplayName("GET /api/v1/telemetry/resource/{resourceId} - Should return 404 when resource missing")
    void shouldReturn404WhenResourceNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(telemetryService.getTelemetryByResourceId(missingId))
                .thenThrow(new ResourceNotFoundException("Resource with ID '" + missingId + "' not found"));

        mockMvc.perform(get("/api/v1/telemetry/resource/{resourceId}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }
}
