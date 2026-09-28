package com.aurora.platform.intelligence.graph.infrastructure.adapter;

import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalCoOccurrenceQueryAdapterTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentAnomalyEvidenceRepository evidenceRepository;

    private HistoricalCoOccurrenceQueryAdapter adapter;

    private UUID investigatedResourceId;
    private UUID candidateResourceId;
    private UUID currentIncidentId;
    private Instant currentIncidentDetectedAt;

    @BeforeEach
    void setUp() {
        adapter = new HistoricalCoOccurrenceQueryAdapter(incidentRepository, evidenceRepository);
        investigatedResourceId = UUID.randomUUID();
        candidateResourceId = UUID.randomUUID();
        currentIncidentId = UUID.randomUUID();
        currentIncidentDetectedAt = Instant.parse("2026-09-27T12:00:00Z");
    }

    @Test
    @DisplayName("Requirement 13: Query explicitly requests IncidentStatus.RESOLVED only")
    void testQueryRequestsResolvedOnly() {
        when(incidentRepository.findHistoricalResolvedIncidents(
                eq(investigatedResourceId),
                eq(IncidentStatus.RESOLVED),
                eq(currentIncidentDetectedAt),
                eq(currentIncidentId),
                any(Pageable.class)
        )).thenReturn(Collections.emptyList());

        adapter.findHistoricalResolvedIncidents(
                investigatedResourceId,
                currentIncidentDetectedAt,
                currentIncidentId,
                100
        );

        verify(incidentRepository).findHistoricalResolvedIncidents(
                eq(investigatedResourceId),
                eq(IncidentStatus.RESOLVED),
                eq(currentIncidentDetectedAt),
                eq(currentIncidentId),
                any(Pageable.class)
        );
    }

    @Test
    @DisplayName("Requirement 14: Target incident itself is excluded from query via excludedIncidentId parameter")
    void testTargetIncidentExcluded() {
        when(incidentRepository.findHistoricalResolvedIncidents(
                eq(investigatedResourceId),
                eq(IncidentStatus.RESOLVED),
                eq(currentIncidentDetectedAt),
                eq(currentIncidentId),
                any(Pageable.class)
        )).thenReturn(Collections.emptyList());

        adapter.findHistoricalResolvedIncidents(
                investigatedResourceId,
                currentIncidentDetectedAt,
                currentIncidentId,
                100
        );

        ArgumentCaptor<UUID> excludedCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(incidentRepository).findHistoricalResolvedIncidents(
                any(), any(), any(), excludedCaptor.capture(), any()
        );

        assertThat(excludedCaptor.getValue()).isEqualTo(currentIncidentId);
    }

    @Test
    @DisplayName("Requirement 15: Future and concurrent incidents are excluded via beforeDetectedAt parameter")
    void testFutureIncidentsExcludedViaTimestampBound() {
        when(incidentRepository.findHistoricalResolvedIncidents(
                eq(investigatedResourceId),
                eq(IncidentStatus.RESOLVED),
                eq(currentIncidentDetectedAt),
                eq(currentIncidentId),
                any(Pageable.class)
        )).thenReturn(Collections.emptyList());

        adapter.findHistoricalResolvedIncidents(
                investigatedResourceId,
                currentIncidentDetectedAt,
                currentIncidentId,
                100
        );

        ArgumentCaptor<Instant> beforeCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(incidentRepository).findHistoricalResolvedIncidents(
                any(), any(), beforeCaptor.capture(), any(), any()
        );

        assertThat(beforeCaptor.getValue()).isEqualTo(currentIncidentDetectedAt);
    }

    @Test
    @DisplayName("Requirement 16: Population is strictly resource-specific to investigatedResourceId u")
    void testPopulationIsResourceSpecific() {
        when(incidentRepository.findHistoricalResolvedIncidents(
                eq(investigatedResourceId),
                eq(IncidentStatus.RESOLVED),
                eq(currentIncidentDetectedAt),
                eq(currentIncidentId),
                any(Pageable.class)
        )).thenReturn(Collections.emptyList());

        adapter.findHistoricalResolvedIncidents(
                investigatedResourceId,
                currentIncidentDetectedAt,
                currentIncidentId,
                100
        );

        ArgumentCaptor<UUID> resourceCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(incidentRepository).findHistoricalResolvedIncidents(
                resourceCaptor.capture(), any(), any(), any(), any()
        );

        assertThat(resourceCaptor.getValue()).isEqualTo(investigatedResourceId);
    }

    @Test
    @DisplayName("Requirement 17: Query limit is strictly bounded by K_MAX = 100")
    void testQueryLimitBoundedByKMax() {
        when(incidentRepository.findHistoricalResolvedIncidents(
                any(), any(), any(), any(), any()
        )).thenReturn(Collections.emptyList());

        // Pass 500 to test clamp down to 100
        adapter.findHistoricalResolvedIncidents(
                investigatedResourceId,
                currentIncidentDetectedAt,
                currentIncidentId,
                500
        );

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findHistoricalResolvedIncidents(
                any(), any(), any(), any(), pageableCaptor.capture()
        );

        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("Requirement 18: Historical query preserves deterministic ordering (PageRequest page 0)")
    void testQueryUsesDeterministicPageRequest() {
        when(incidentRepository.findHistoricalResolvedIncidents(
                any(), any(), any(), any(), any()
        )).thenReturn(Collections.emptyList());

        adapter.findHistoricalResolvedIncidents(
                investigatedResourceId,
                currentIncidentDetectedAt,
                currentIncidentId,
                50
        );

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findHistoricalResolvedIncidents(
                any(), any(), any(), any(), pageableCaptor.capture()
        );

        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 50));
    }

    @Test
    @DisplayName("Requirement 19: Anomaly qualification checks qualifying evidence within incident lookback window")
    void testAnomalyQualificationInLookbackWindow() {
        Instant histTime1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant histTime2 = Instant.parse("2026-09-25T14:00:00Z");

        IncidentEntity inc1 = IncidentEntity.builder()
                .id(UUID.randomUUID()).resourceId(investigatedResourceId).status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.HIGH).detectedAt(histTime1).build();
        IncidentEntity inc2 = IncidentEntity.builder()
                .id(UUID.randomUUID()).resourceId(investigatedResourceId).status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.MEDIUM).detectedAt(histTime2).build();

        List<IncidentEntity> historicalIncidents = List.of(inc1, inc2);

        // Candidate v had an anomaly 5 minutes prior to histTime1 (qualifies!)
        Instant candidateAnomalyTime = histTime1.minusSeconds(300);
        IncidentAnomalyEvidenceEntity ev = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(candidateResourceId).incidentId(inc1.getId())
                .metricName("db_pool").observedValue(98.0).anomalyScore(0.9)
                .observedAt(candidateAnomalyTime).build();

        when(evidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(candidateResourceId), any(Instant.class), any(Instant.class)
        )).thenReturn(List.of(ev));

        when(evidenceRepository.findByIncidentIdIn(any())).thenReturn(List.of(ev));

        long coOccurrences = adapter.countAnomalousCoOccurrences(
                candidateResourceId,
                historicalIncidents,
                10L
        );

        // inc1 qualifies, inc2 does not -> coOccurrences = 1
        assertThat(coOccurrences).isEqualTo(1L);
    }

    @Test
    @DisplayName("Requirement 20: Dependency direction correctness — evidence is checked on upstream candidate v, not u")
    void testDependencyDirectionChecksCandidateV() {
        Instant histTime = Instant.parse("2026-09-26T10:00:00Z");
        IncidentEntity inc = IncidentEntity.builder()
                .id(UUID.randomUUID()).resourceId(investigatedResourceId).status(IncidentStatus.RESOLVED)
                .severity(IncidentSeverity.HIGH).detectedAt(histTime).build();

        when(evidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(candidateResourceId), any(), any()
        )).thenReturn(Collections.emptyList());
        when(evidenceRepository.findByIncidentIdIn(any())).thenReturn(Collections.emptyList());

        adapter.countAnomalousCoOccurrences(
                candidateResourceId,
                List.of(inc),
                10L
        );

        // Verify that the evidence repository is queried specifically for candidateResourceId (v)
        verify(evidenceRepository).findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(candidateResourceId), any(), any()
        );
    }
}
