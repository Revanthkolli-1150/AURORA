package com.aurora.platform.intelligence.rca.controller;

import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.dto.RcaEvidenceResponse;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceType;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RcaController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class})
class RcaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RcaAnalysisService rcaAnalysisService;

    @Test
    @DisplayName("POST /api/v1/incidents/{id}/rca returns 201 Created with full RCA analysis")
    void testAnalyzeIncidentReturns201() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        RcaEvidenceResponse evidence = new RcaEvidenceResponse(
                UUID.randomUUID(), candidateId, RcaEvidenceType.ANOMALY, resourceId,
                "db_connection_utilization", 97.0, 0.86, Instant.now(), 0.40,
                "Exhibited anomaly", Instant.now()
        );

        RcaCandidateResponse candidate = new RcaCandidateResponse(
                candidateId, analysisId, resourceId, "db_connection_utilization",
                "Database connection pool exhaustion", 0.85, 1,
                "PostgreSQL is candidate root cause", true, Instant.now(), List.of(evidence)
        );

        RcaAnalysisResponse response = new RcaAnalysisResponse(
                analysisId, incidentId, RcaAnalysisStatus.COMPLETED, resourceId,
                "RCA analysis completed", 0.85, "VERY_HIGH", Instant.now(), Instant.now(), Instant.now(),
                List.of(candidate)
        );

        when(rcaAnalysisService.analyzeIncident(incidentId)).thenReturn(response);

        mockMvc.perform(post("/api/v1/incidents/{incidentId}/rca", incidentId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andExpect(jsonPath("$.id").value(analysisId.toString()))
                .andExpect(jsonPath("$.incidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.confidence").value(0.85))
                .andExpect(jsonPath("$.confidenceLevel").value("VERY_HIGH"))
                .andExpect(jsonPath("$.candidates[0].rank").value(1))
                .andExpect(jsonPath("$.candidates[0].primaryCandidate").value(true))
                .andExpect(jsonPath("$.candidates[0].evidenceScore").value(0.85))
                .andExpect(jsonPath("$.candidates[0].evidence[0].evidenceType").value("ANOMALY"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/rca returns 200 OK with latest RCA analysis")
    void testGetLatestRcaAnalysisReturns200() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        RcaAnalysisResponse response = new RcaAnalysisResponse(
                analysisId, incidentId, RcaAnalysisStatus.COMPLETED, resourceId,
                "RCA analysis completed", 0.85, "VERY_HIGH", Instant.now(), Instant.now(), Instant.now(),
                List.of()
        );

        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/rca", incidentId))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andExpect(jsonPath("$.id").value(analysisId.toString()))
                .andExpect(jsonPath("$.incidentId").value(incidentId.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/rca returns 404 Not Found when no analysis exists")
    void testGetLatestRcaAnalysisNotFound() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId))
                .thenThrow(new ResourceNotFoundException("No RCA analysis found for incident ID: " + incidentId));

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/rca", incidentId))
                .andExpect(status().isNotFound())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/incidents/{id}/rca/{analysisId} returns 200 OK")
    void testGetRcaAnalysisByIdReturns200() throws Exception {
        UUID incidentId = UUID.randomUUID();
        UUID analysisId = UUID.randomUUID();

        RcaAnalysisResponse response = new RcaAnalysisResponse(
                analysisId, incidentId, RcaAnalysisStatus.COMPLETED, UUID.randomUUID(),
                "Summary", 0.85, "VERY_HIGH", Instant.now(), Instant.now(), Instant.now(),
                List.of()
        );

        when(rcaAnalysisService.getAnalysisById(incidentId, analysisId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/incidents/{incidentId}/rca/{analysisId}", incidentId, analysisId))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .andExpect(jsonPath("$.id").value(analysisId.toString()));
    }
}
