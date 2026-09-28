package com.aurora.platform.intelligence.rca.service;

import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.anomaly.service.AnomalyDetectionService;
import com.aurora.platform.intelligence.rca.application.EdgeWeightProvider;
import com.aurora.platform.intelligence.rca.application.EdgeWeightResult;
import com.aurora.platform.intelligence.rca.config.RcaProperties;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.dto.RcaEvidenceResponse;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.entity.RcaCandidateEntity;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceEntity;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceType;
import com.aurora.platform.intelligence.rca.repository.RcaAnalysisRepository;
import com.aurora.platform.intelligence.rca.repository.RcaCandidateRepository;
import com.aurora.platform.intelligence.rca.repository.RcaEvidenceRepository;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.repository.ResourceRepository;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RcaAnalysisServiceGraphWeightingTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentAnomalyEvidenceRepository incidentEvidenceRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ResourceDependencyRepository dependencyRepository;

    @Mock
    private TelemetryEventRepository telemetryEventRepository;

    @Mock
    private AnomalyDetectionService anomalyDetectionService;

    @Mock
    private RcaAnalysisRepository analysisRepository;

    @Mock
    private RcaCandidateRepository candidateRepository;

    @Mock
    private RcaEvidenceRepository evidenceRepository;

    @Mock
    private EdgeWeightProvider edgeWeightProvider;

    private RcaProperties properties;
    private RcaAnalysisServiceImpl serviceWithGraph;

    private UUID incidentId;
    private UUID apiResourceId;
    private UUID dbResourceId;

    private ResourceEntity apiResource;
    private ResourceEntity dbResource;

    @BeforeEach
    void setUp() {
        properties = new RcaProperties();
        properties.setLookbackMinutes(10L);

        serviceWithGraph = new RcaAnalysisServiceImpl(
                incidentRepository,
                incidentEvidenceRepository,
                resourceRepository,
                dependencyRepository,
                telemetryEventRepository,
                anomalyDetectionService,
                analysisRepository,
                candidateRepository,
                evidenceRepository,
                properties,
                edgeWeightProvider
        );

        incidentId = UUID.randomUUID();
        apiResourceId = UUID.randomUUID();
        dbResourceId = UUID.randomUUID();

        apiResource = ResourceEntity.builder().id(apiResourceId).name("api-service")
                .type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();
        dbResource = ResourceEntity.builder().id(dbResourceId).name("database")
                .type(ResourceType.DATABASE).status(ResourceStatus.HEALTHY).build();

        // Lenient save mocks
        org.mockito.Mockito.lenient().when(analysisRepository.save(any(RcaAnalysisEntity.class))).thenAnswer(inv -> {
            RcaAnalysisEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        org.mockito.Mockito.lenient().when(candidateRepository.save(any(RcaCandidateEntity.class))).thenAnswer(inv -> {
            RcaCandidateEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        org.mockito.Mockito.lenient().when(evidenceRepository.save(any(RcaEvidenceEntity.class))).thenAnswer(inv -> {
            RcaEvidenceEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    @DisplayName("Requirement 21 & 25: Learned dependency weight replaces static 0.20 and candidate score remains additive")
    void testLearnedDependencyWeightReplacesStaticWeight() {
        Instant incidentTime = Instant.parse("2026-09-27T12:00:00Z");
        Instant dbAnomalyTime = Instant.parse("2026-09-27T11:58:00Z"); // 120s prior

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime)
                .title("API Latency Degradation").severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.DETECTED).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(dbResourceId)).thenReturn(Optional.of(dbResource));

        // Dependency: API depends on DB
        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(dbResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        // API anomaly evidence
        IncidentAnomalyEvidenceEntity apiEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).incidentId(incidentId).resourceId(apiResourceId)
                .metricName("response_time").observedValue(600.0).anomalyScore(0.85).observedAt(incidentTime).build();
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(List.of(apiEvidence));

        // DB anomaly evidence (preceded incident)
        IncidentAnomalyEvidenceEntity dbEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(dbResourceId).metricName("connections")
                .observedValue(95.0).anomalyScore(0.88).observedAt(dbAnomalyTime).build();
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(dbResourceId), any(), any())).thenReturn(List.of(dbEvidence));

        // Learned edge weight: 0.35 (instead of static 0.20!)
        EdgeWeightResult learnedResult = new EdgeWeightResult(
                0.35, 0.35, 10L, 5L, false,
                "Empirical edge weight: 0.35 (raw: 0.35, prior: 0.20, smoothing beta: 5.0, co-occurrences: 5/10 incidents, fallback: false)."
        );
        when(edgeWeightProvider.getEdgeWeight(eq(apiResourceId), eq(dbResourceId), any(), eq(incidentId)))
                .thenReturn(learnedResult);

        // Execute RCA
        RcaAnalysisResponse response = serviceWithGraph.analyzeIncident(incidentId);

        // Assert DB candidate received learned weight
        RcaCandidateResponse dbCandidate = response.candidates().stream()
                .filter(c -> c.candidateResourceId().equals(dbResourceId)).findFirst().orElseThrow();

        // Score: ANOMALY (0.40) + TEMPORAL_PRECEDENCE (0.25) + DEPENDENCY (0.35) = 1.00!
        assertThat(dbCandidate.evidenceScore()).isEqualTo(1.00);

        RcaEvidenceResponse depEvidence = dbCandidate.evidence().stream()
                .filter(e -> e.evidenceType() == RcaEvidenceType.DEPENDENCY).findFirst().orElseThrow();
        assertThat(depEvidence.contributionScore()).isEqualTo(0.35);
        assertThat(depEvidence.explanation()).contains("Empirical edge weight: 0.35");
    }

    @Test
    @DisplayName("Requirements 22, 23, 24: ANOMALY (0.40), TEMPORAL (0.25), and TELEMETRY (0.15) remain unchanged")
    void testBaselineWeightsRemainUnchanged() {
        assertThat(RcaAnalysisServiceImpl.WEIGHT_ANOMALY).isEqualTo(0.40);
        assertThat(RcaAnalysisServiceImpl.WEIGHT_TEMPORAL_PRECEDENCE).isEqualTo(0.25);
        assertThat(RcaAnalysisServiceImpl.WEIGHT_TELEMETRY_CORRELATION).isEqualTo(0.15);
        assertThat(RcaAnalysisServiceImpl.WEIGHT_DEPENDENCY).isEqualTo(0.20);
    }

    @Test
    @DisplayName("Requirement 26: evidence_score semantics remain an additive evidence index bounded to [0.00, 1.00]")
    void testEvidenceScoreSemanticsUnchanged() {
        Instant incidentTime = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime)
                .title("API Latency").status(IncidentStatus.DETECTED).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(dbResourceId)).thenReturn(Optional.of(dbResource));

        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(dbResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());

        // Max clamped edge weight: 0.40
        EdgeWeightResult maxResult = new EdgeWeightResult(
                0.40, 0.60, 20L, 20L, false,
                "Empirical edge weight: 0.40 (raw: 0.60, prior: 0.20, smoothing beta: 5.0, co-occurrences: 20/20 incidents, fallback: false)."
        );
        when(edgeWeightProvider.getEdgeWeight(any(), any(), any(), any())).thenReturn(maxResult);

        // DB has anomaly and precedence
        IncidentAnomalyEvidenceEntity dbAnom = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(dbResourceId).metricName("connections")
                .observedValue(99.0).anomalyScore(0.95).observedAt(incidentTime.minusSeconds(10)).build();
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(dbResourceId), any(), any())).thenReturn(List.of(dbAnom));

        RcaAnalysisResponse response = serviceWithGraph.analyzeIncident(incidentId);

        RcaCandidateResponse dbCand = response.candidates().get(0);
        // ANOMALY 0.40 + PRECEDENCE 0.25 + DEPENDENCY 0.40 = 1.05 -> capped at 1.00!
        assertThat(dbCand.evidenceScore()).isEqualTo(1.00);
    }

    @Test
    @DisplayName("Requirements 27 & 28: Healthy dependency cannot become PRIMARY from edge weight alone (anomaly prerequisite preserved)")
    void testHealthyDependencyCannotBecomePrimary() {
        Instant incidentTime = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime)
                .title("API Latency").status(IncidentStatus.DETECTED).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(dbResourceId)).thenReturn(Optional.of(dbResource));

        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(dbResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        // API has NO anomaly evidence
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());

        // DB has NO anomaly evidence either (healthy dependency)
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(dbResourceId), any(), any())).thenReturn(Collections.emptyList());

        // DB gets high edge weight: 0.40 >= MIN_MEANINGFUL_SCORE (0.30)
        EdgeWeightResult highEdge = new EdgeWeightResult(
                0.40, 0.40, 10L, 8L, false, "Empirical edge weight: 0.40."
        );
        when(edgeWeightProvider.getEdgeWeight(any(), any(), any(), any())).thenReturn(highEdge);

        RcaAnalysisResponse response = serviceWithGraph.analyzeIncident(incidentId);

        // DB has evidence score 0.40 >= 0.30, but NO anomaly evidence!
        // Safety invariant: MUST NOT BE PRIMARY! Status must be INSUFFICIENT_EVIDENCE!
        assertThat(response.status()).isEqualTo(RcaAnalysisStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.candidates()).hasSize(1);
        RcaCandidateResponse dbCandidate = response.candidates().get(0);
        assertThat(dbCandidate.primaryCandidate()).isFalse();
        assertThat(dbCandidate.explanation()).contains("exhibited no anomalous telemetry or evidence");
    }

    @Test
    @DisplayName("Requirement 29: Database failure falls back synchronously to static prior 0.20")
    void testDatabaseFailureFallsBackToPrior() {
        Instant incidentTime = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime)
                .title("API Latency").status(IncidentStatus.DETECTED).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(dbResourceId)).thenReturn(Optional.of(dbResource));

        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(dbResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(dbResourceId), any(), any())).thenReturn(Collections.emptyList());

        // Provider returns fallback due to query error
        EdgeWeightResult fallbackResult = EdgeWeightResult.fallback(0.20, "Empirical edge weight: 0.20 (prior: 0.20, fallback: true - query error).");
        when(edgeWeightProvider.getEdgeWeight(any(), any(), any(), any())).thenReturn(fallbackResult);

        RcaAnalysisResponse response = serviceWithGraph.analyzeIncident(incidentId);

        RcaCandidateResponse dbCandidate = response.candidates().get(0);
        assertThat(dbCandidate.evidenceScore()).isEqualTo(0.20);
        RcaEvidenceResponse depEv = dbCandidate.evidence().get(0);
        assertThat(depEv.contributionScore()).isEqualTo(0.20);
        assertThat(depEv.explanation()).contains("fallback: true - query error");
    }

    @Test
    @DisplayName("Requirement 30: Zero history falls back to static prior 0.20")
    void testZeroHistoryFallsBackToPrior() {
        Instant incidentTime = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime)
                .title("API Latency").status(IncidentStatus.DETECTED).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(dbResourceId)).thenReturn(Optional.of(dbResource));

        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(dbResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(dbResourceId), any(), any())).thenReturn(Collections.emptyList());

        EdgeWeightResult zeroHistoryResult = new EdgeWeightResult(
                0.20, 0.20, 0L, 0L, true,
                "Empirical edge weight: 0.20 (prior: 0.20, smoothing beta: 5.0, co-occurrences: 0/0 incidents, fallback: true - zero historical incidents)."
        );
        when(edgeWeightProvider.getEdgeWeight(any(), any(), any(), any())).thenReturn(zeroHistoryResult);

        RcaAnalysisResponse response = serviceWithGraph.analyzeIncident(incidentId);

        RcaCandidateResponse dbCandidate = response.candidates().get(0);
        assertThat(dbCandidate.evidenceScore()).isEqualTo(0.20);
        RcaEvidenceResponse depEv = dbCandidate.evidence().get(0);
        assertThat(depEv.contributionScore()).isEqualTo(0.20);
        assertThat(depEv.explanation()).contains("zero historical incidents");
    }
}
