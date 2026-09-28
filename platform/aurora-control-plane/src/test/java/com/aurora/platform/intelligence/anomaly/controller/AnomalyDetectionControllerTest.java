package com.aurora.platform.intelligence.anomaly.controller;

import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.intelligence.anomaly.service.AnomalyDetectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnomalyDetectionController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class AnomalyDetectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnomalyDetectionService anomalyDetectionService;

    @Test
    @DisplayName("GET /api/v1/intelligence/anomaly/resource/{resourceId} - Should return 200 OK with anomaly response")
    void shouldReturn200WithAnomalyResponse() throws Exception {
        UUID resourceId = UUID.randomUUID();
        Instant now = Instant.now();

        AnomalyDetectionResponse response = new AnomalyDetectionResponse(
                resourceId,
                "cpu_usage",
                91.0,
                "Z_SCORE",
                AnomalyStatus.ANOMALOUS,
                1.0,
                3.75,
                3.0,
                16,
                44.1875,
                1.167,
                42.0,
                46.0,
                now
        );

        when(anomalyDetectionService.evaluateAnomaly(eq(resourceId), eq("cpu_usage"), eq(null), eq(null)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/intelligence/anomaly/resource/{resourceId}", resourceId)
                        .param("metricName", "cpu_usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value(resourceId.toString()))
                .andExpect(jsonPath("$.metricName").value("cpu_usage"))
                .andExpect(jsonPath("$.currentValue").value(91.0))
                .andExpect(jsonPath("$.status").value("ANOMALOUS"))
                .andExpect(jsonPath("$.detectionMethod").value("Z_SCORE"))
                .andExpect(jsonPath("$.anomalyScore").value(1.0))
                .andExpect(jsonPath("$.zScore").value(3.75))
                .andExpect(jsonPath("$.threshold").value(3.0))
                .andExpect(jsonPath("$.sampleCount").value(16));
    }

    @Test
    @DisplayName("GET /api/v1/intelligence/anomaly/resource/{resourceId} - With explicit value and threshold")
    void shouldEvaluateWithExplicitValueAndThreshold() throws Exception {
        UUID resourceId = UUID.randomUUID();

        AnomalyDetectionResponse response = new AnomalyDetectionResponse(
                resourceId,
                "cpu_usage",
                44.0,
                "Z_SCORE",
                AnomalyStatus.NORMAL,
                0.2,
                0.6,
                3.0,
                16,
                44.1875,
                1.167,
                42.0,
                46.0,
                Instant.now()
        );

        when(anomalyDetectionService.evaluateAnomaly(eq(resourceId), eq("cpu_usage"), eq(44.0), eq(3.0)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/intelligence/anomaly/resource/{resourceId}", resourceId)
                        .param("metricName", "cpu_usage")
                        .param("value", "44.0")
                        .param("threshold", "3.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NORMAL"))
                .andExpect(jsonPath("$.currentValue").value(44.0));
    }

    @Test
    @DisplayName("GET /api/v1/intelligence/anomaly/resource/{resourceId} - Should return 400 when metricName is missing")
    void shouldReturn400WhenMetricNameIsMissing() throws Exception {
        UUID resourceId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/intelligence/anomaly/resource/{resourceId}", resourceId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/intelligence/anomaly/resource/{resourceId} - Should return 404 when resource not found")
    void shouldReturn404WhenResourceNotFound() throws Exception {
        UUID unknownId = UUID.randomUUID();

        when(anomalyDetectionService.evaluateAnomaly(eq(unknownId), eq("cpu_usage"), eq(null), eq(null)))
                .thenThrow(new ResourceNotFoundException("Resource with ID '" + unknownId + "' not found"));

        mockMvc.perform(get("/api/v1/intelligence/anomaly/resource/{resourceId}", unknownId)
                        .param("metricName", "cpu_usage"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }
}
