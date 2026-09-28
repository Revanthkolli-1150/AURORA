package com.aurora.platform.intelligence.graph.service;

import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.intelligence.graph.application.port.out.HistoricalCoOccurrenceQueryPort;
import com.aurora.platform.intelligence.graph.config.GraphIntelligenceProperties;
import com.aurora.platform.intelligence.rca.application.EdgeWeightResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmpiricalEdgeWeightProviderTest {

    @Mock
    private HistoricalCoOccurrenceQueryPort queryPort;

    private GraphIntelligenceProperties properties;
    private EmpiricalEdgeWeightProvider provider;

    private UUID investigatedResourceId;
    private UUID candidateResourceId;
    private UUID targetIncidentId;
    private Instant targetIncidentDetectedAt;

    @BeforeEach
    void setUp() {
        properties = new GraphIntelligenceProperties();
        properties.setSmoothingBeta(5.0);
        properties.setMaxHistoryIncidents(100);
        properties.setLookbackMinutes(10L);
        properties.setEnabled(true);

        provider = new EmpiricalEdgeWeightProvider(queryPort, properties);

        investigatedResourceId = UUID.randomUUID();
        candidateResourceId = UUID.randomUUID();
        targetIncidentId = UUID.randomUUID();
        targetIncidentDetectedAt = Instant.parse("2026-09-27T12:00:00Z");
    }

    @Test
    @DisplayName("Cold start (zero historical incidents) returns prior 0.20 with fallback=true")
    void testColdStartReturnsPrior() {
        when(queryPort.findHistoricalResolvedIncidents(
                eq(investigatedResourceId),
                eq(targetIncidentDetectedAt),
                eq(targetIncidentId),
                anyInt()
        )).thenReturn(Collections.emptyList());

        EdgeWeightResult result = provider.getEdgeWeight(
                investigatedResourceId,
                candidateResourceId,
                targetIncidentDetectedAt,
                targetIncidentId
        );

        assertThat(result.finalWeight()).isEqualTo(0.20);
        assertThat(result.incidentCount()).isEqualTo(0L);
        assertThat(result.coOccurCount()).isEqualTo(0L);
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.explanation()).contains("zero historical incidents");
    }

    @Test
    @DisplayName("Learned edge weight calculation with 5 incidents and 3 co-occurrences -> (3 + 1) / (5 + 5) = 0.40")
    void testLearnedEdgeWeightCalculation() {
        List<IncidentEntity> incidents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            incidents.add(IncidentEntity.builder()
                    .id(UUID.randomUUID()).resourceId(investigatedResourceId)
                    .status(IncidentStatus.RESOLVED).severity(IncidentSeverity.HIGH)
                    .detectedAt(targetIncidentDetectedAt.minusSeconds(3600 * (i + 1)))
                    .build());
        }

        when(queryPort.findHistoricalResolvedIncidents(any(), any(), any(), anyInt()))
                .thenReturn(incidents);
        when(queryPort.countAnomalousCoOccurrences(eq(candidateResourceId), eq(incidents), anyLong()))
                .thenReturn(3L);

        EdgeWeightResult result = provider.getEdgeWeight(
                investigatedResourceId,
                candidateResourceId,
                targetIncidentDetectedAt,
                targetIncidentId
        );

        assertThat(result.finalWeight()).isEqualTo(0.40);
        assertThat(result.rawWeight()).isEqualTo(0.40);
        assertThat(result.incidentCount()).isEqualTo(5L);
        assertThat(result.coOccurCount()).isEqualTo(3L);
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.explanation()).contains("co-occurrences: 3/5 incidents");
    }

    @Test
    @DisplayName("Database query exception triggers synchronous graceful fallback to prior 0.20")
    void testQueryExceptionFallsBackGracefully() {
        when(queryPort.findHistoricalResolvedIncidents(any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("Database connection timeout"));

        EdgeWeightResult result = provider.getEdgeWeight(
                investigatedResourceId,
                candidateResourceId,
                targetIncidentDetectedAt,
                targetIncidentId
        );

        assertThat(result.finalWeight()).isEqualTo(0.20);
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.explanation()).contains("query error");
    }

    @Test
    @DisplayName("Disabled by configuration returns static prior 0.20 immediately")
    void testDisabledReturnsPrior() {
        properties.setEnabled(false);

        EdgeWeightResult result = provider.getEdgeWeight(
                investigatedResourceId,
                candidateResourceId,
                targetIncidentDetectedAt,
                targetIncidentId
        );

        assertThat(result.finalWeight()).isEqualTo(0.20);
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.explanation()).contains("disabled by configuration");
    }

    @Test
    @DisplayName("Determinism: Repeated calculations on identical input produce identical output across 10 runs")
    void testRepeatedRunsDeterminism() {
        List<IncidentEntity> incidents = List.of(
                IncidentEntity.builder().id(UUID.randomUUID()).resourceId(investigatedResourceId)
                        .status(IncidentStatus.RESOLVED).severity(IncidentSeverity.MEDIUM)
                        .detectedAt(targetIncidentDetectedAt.minusSeconds(1000)).build(),
                IncidentEntity.builder().id(UUID.randomUUID()).resourceId(investigatedResourceId)
                        .status(IncidentStatus.RESOLVED).severity(IncidentSeverity.HIGH)
                        .detectedAt(targetIncidentDetectedAt.minusSeconds(2000)).build()
        );

        when(queryPort.findHistoricalResolvedIncidents(any(), any(), any(), anyInt()))
                .thenReturn(incidents);
        when(queryPort.countAnomalousCoOccurrences(eq(candidateResourceId), eq(incidents), anyLong()))
                .thenReturn(1L);

        EdgeWeightResult baseline = provider.getEdgeWeight(
                investigatedResourceId, candidateResourceId, targetIncidentDetectedAt, targetIncidentId
        );

        for (int i = 0; i < 10; i++) {
            EdgeWeightResult current = provider.getEdgeWeight(
                    investigatedResourceId, candidateResourceId, targetIncidentDetectedAt, targetIncidentId
            );
            assertThat(current.finalWeight()).isEqualTo(baseline.finalWeight());
            assertThat(current.rawWeight()).isEqualTo(baseline.rawWeight());
            assertThat(current.explanation()).isEqualTo(baseline.explanation());
            assertThat(current.fallbackUsed()).isEqualTo(baseline.fallbackUsed());
        }
    }
}
