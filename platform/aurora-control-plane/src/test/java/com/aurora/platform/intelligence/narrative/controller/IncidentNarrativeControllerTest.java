package com.aurora.platform.intelligence.narrative.controller;

import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.intelligence.narrative.dto.IncidentNarrativeResponse;
import com.aurora.platform.intelligence.narrative.dto.NarrativeMetadataResponse;
import com.aurora.platform.intelligence.narrative.service.IncidentNarrativeService;
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
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentNarrativeController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class IncidentNarrativeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IncidentNarrativeService narrativeService;

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/narrative - Should return 200 OK with generated narrative")
    void shouldGenerateNarrativeSuccessfully() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID rcaId = UUID.randomUUID();
        UUID narrativeId = UUID.randomUUID();

        NarrativeMetadataResponse metadata = new NarrativeMetadataResponse(
                "openai",
                "gpt-4o",
                "operator-narrative-v1",
                false,
                150,
                Instant.now()
        );

        IncidentNarrativeResponse response = new IncidentNarrativeResponse(
                narrativeId,
                incidentId,
                rcaId,
                "Payment Latency Headline",
                "Payment latency triggered downstream queue saturation.",
                List.of("504 Gateway Timeout spike", "Worker pool exhaustion"),
                "Postgres connection starvation",
                List.of("Redis eviction surge"),
                "Matches historical pattern",
                List.of("Check pg_stat_activity", "Inspect active connection pool"),
                List.of("Runbook is informational only"),
                metadata
        );

        when(narrativeService.generateOrGetNarrative(incidentId)).thenReturn(response);

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/narrative", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(narrativeId.toString())))
                .andExpect(jsonPath("$.incidentId", is(incidentId.toString())))
                .andExpect(jsonPath("$.rcaAnalysisId", is(rcaId.toString())))
                .andExpect(jsonPath("$.headline", is("Payment Latency Headline")))
                .andExpect(jsonPath("$.executiveSummary", is("Payment latency triggered downstream queue saturation.")))
                .andExpect(jsonPath("$.observedSymptoms", hasSize(2)))
                .andExpect(jsonPath("$.rootCauseExplanation", is("Postgres connection starvation")))
                .andExpect(jsonPath("$.metadata.provider", is("openai")))
                .andExpect(jsonPath("$.metadata.fallbackUsed", is(false)));

        verify(narrativeService).generateOrGetNarrative(incidentId);
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/narrative - Should return 404 when incident not found")
    void shouldReturn404WhenIncidentNotFound() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(narrativeService.generateOrGetNarrative(incidentId))
                .thenThrow(new ResourceNotFoundException("Incident not found: " + incidentId));

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/narrative", incidentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Incident not found: " + incidentId)));
    }

    @Test
    @DisplayName("POST /api/v1/incidents/{incidentId}/narrative - Should return 404 when no completed RCA found")
    void shouldReturn404WhenNoRcaFound() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(narrativeService.generateOrGetNarrative(incidentId))
                .thenThrow(new ResourceNotFoundException("No completed RCA analysis found for incident: " + incidentId));

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/narrative", incidentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("No completed RCA analysis found for incident: " + incidentId)));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{incidentId}/narrative - Should return 200 OK with cached narrative")
    void shouldGetCachedNarrative() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID rcaId = UUID.randomUUID();
        UUID narrativeId = UUID.randomUUID();

        NarrativeMetadataResponse metadata = new NarrativeMetadataResponse(
                "deterministic-fallback",
                "deterministic-rule-engine",
                "operator-narrative-v1",
                true,
                5,
                Instant.now()
        );

        IncidentNarrativeResponse response = new IncidentNarrativeResponse(
                narrativeId,
                incidentId,
                rcaId,
                "Fallback Headline",
                "Deterministic fallback summary",
                List.of("Symptom fallback"),
                "Hypothesis fallback",
                List.of(),
                null,
                List.of("Step fallback"),
                List.of("Caveat fallback"),
                metadata
        );

        when(narrativeService.getNarrative(incidentId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/narrative", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(narrativeId.toString())))
                .andExpect(jsonPath("$.metadata.fallbackUsed", is(true)))
                .andExpect(jsonPath("$.metadata.provider", is("deterministic-fallback")));

        verify(narrativeService).getNarrative(incidentId);
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{incidentId}/narrative - Should return 404 when no narrative cached")
    void shouldReturn404WhenNoNarrativeFound() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(narrativeService.getNarrative(incidentId))
                .thenThrow(new ResourceNotFoundException("No narrative found for incident: " + incidentId));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/narrative", incidentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("No narrative found for incident: " + incidentId)));
    }
}
