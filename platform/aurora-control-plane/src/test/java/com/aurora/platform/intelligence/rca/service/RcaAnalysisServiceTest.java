package com.aurora.platform.intelligence.rca.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.anomaly.service.AnomalyDetectionService;
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
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.resource.repository.ResourceRepository;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RcaAnalysisServiceTest {

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

    private RcaProperties properties;
    private RcaAnalysisServiceImpl service;

    private UUID incidentId;
    private UUID apiResourceId;
    private UUID postgresResourceId;
    private UUID redisResourceId;
    private UUID frontendResourceId;
    private UUID unrelatedResourceId;

    private ResourceEntity apiResource;
    private ResourceEntity postgresResource;
    private ResourceEntity redisResource;
    private ResourceEntity frontendResource;

    @BeforeEach
    void setUp() {
        properties = new RcaProperties();
        properties.setLookbackMinutes(10L);

        service = new RcaAnalysisServiceImpl(
                incidentRepository,
                incidentEvidenceRepository,
                resourceRepository,
                dependencyRepository,
                telemetryEventRepository,
                anomalyDetectionService,
                analysisRepository,
                candidateRepository,
                evidenceRepository,
                properties
        );

        incidentId = UUID.randomUUID();
        apiResourceId = UUID.randomUUID();
        postgresResourceId = UUID.randomUUID();
        redisResourceId = UUID.randomUUID();
        frontendResourceId = UUID.randomUUID();
        unrelatedResourceId = UUID.randomUUID();

        apiResource = ResourceEntity.builder().id(apiResourceId).name("api-gateway").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();
        postgresResource = ResourceEntity.builder().id(postgresResourceId).name("primary-postgres").type(ResourceType.DATABASE).status(ResourceStatus.HEALTHY).build();
        redisResource = ResourceEntity.builder().id(redisResourceId).name("cache-redis").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();
        frontendResource = ResourceEntity.builder().id(frontendResourceId).name("web-frontend").type(ResourceType.APPLICATION).status(ResourceStatus.HEALTHY).build();
    }

    private void mockSaveAnalysis() {
        org.mockito.Mockito.lenient().when(analysisRepository.save(any(RcaAnalysisEntity.class))).thenAnswer(invocation -> {
            RcaAnalysisEntity e = invocation.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        org.mockito.Mockito.lenient().when(candidateRepository.save(any(RcaCandidateEntity.class))).thenAnswer(invocation -> {
            RcaCandidateEntity e = invocation.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        org.mockito.Mockito.lenient().when(evidenceRepository.save(any(RcaEvidenceEntity.class))).thenAnswer(invocation -> {
            RcaEvidenceEntity e = invocation.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
    }

    @Test
    @DisplayName("Section 27 & Requirements: Deterministic Integration Scenario with PostgreSQL, Redis, API, Frontend")
    void testSection27Scenario() {
        Instant apiTime = Instant.parse("2026-09-24T14:32:05Z");
        Instant postgresTime = Instant.parse("2026-09-24T14:30:42Z"); // 83 seconds before

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId)
                .resourceId(apiResourceId)
                .title("API latency anomaly")
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.DETECTED)
                .detectedAt(apiTime)
                .build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(postgresResourceId)).thenReturn(Optional.of(postgresResource));
        when(resourceRepository.findById(redisResourceId)).thenReturn(Optional.of(redisResource));
        when(resourceRepository.findById(frontendResourceId)).thenReturn(Optional.of(frontendResource));

        // API anomaly evidence
        IncidentAnomalyEvidenceEntity apiEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .incidentId(incidentId)
                .resourceId(apiResourceId)
                .metricName("api_response_time")
                .observedValue(850.0)
                .anomalyScore(0.92)
                .observedAt(apiTime)
                .build();
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId))
                .thenReturn(List.of(apiEvidence));

        // Dependencies: frontend -> api; api -> postgres; api -> redis
        ResourceDependencyEntity depPostgres = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(postgresResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        ResourceDependencyEntity depRedis = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(redisResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId))
                .thenReturn(List.of(depPostgres, depRedis));

        // Frontend depends on API (incoming dependent)
        ResourceDependencyEntity depFrontend = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(frontendResourceId).targetResourceId(apiResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId))
                .thenReturn(List.of(depFrontend));

        // PostgreSQL anomaly at 14:30:42Z
        IncidentAnomalyEvidenceEntity postgresAnomaly = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .resourceId(postgresResourceId)
                .metricName("db_connection_utilization")
                .observedValue(97.0)
                .anomalyScore(0.86)
                .observedAt(postgresTime)
                .build();
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(postgresResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(postgresAnomaly));

        // Redis has no anomaly and normal telemetry
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(redisResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        // Frontend has no anomaly
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(frontendResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        mockSaveAnalysis();

        // EXECUTE
        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        // ASSERTIONS
        assertThat(response.status()).isEqualTo(RcaAnalysisStatus.COMPLETED);
        assertThat(response.confidence()).isEqualTo(0.85);
        assertThat(response.confidenceLevel()).isEqualTo("VERY_HIGH");
        assertThat(response.candidates()).hasSize(3); // PostgreSQL, API, Redis

        // Rank 1: PostgreSQL
        RcaCandidateResponse rank1 = response.candidates().get(0);
        assertThat(rank1.rank()).isEqualTo(1);
        assertThat(rank1.candidateResourceId()).isEqualTo(postgresResourceId);
        assertThat(rank1.evidenceScore()).isEqualTo(0.85); // 0.40 (ANOMALY) + 0.25 (TEMPORAL_PRECEDENCE) + 0.20 (DEPENDENCY)
        assertThat(rank1.primaryCandidate()).isTrue();
        assertThat(rank1.explanation()).contains("direct dependency of api-gateway")
                .contains("db_connection_utilization")
                .contains("preceded the incident anomaly by 83 seconds");
        assertThat(rank1.explanation()).doesNotContain("definitely caused");

        // Verify evidence breakdown on PostgreSQL
        List<RcaEvidenceType> types = rank1.evidence().stream().map(RcaEvidenceResponse::evidenceType).toList();
        assertThat(types).containsExactlyInAnyOrder(
                RcaEvidenceType.ANOMALY,
                RcaEvidenceType.TEMPORAL_PRECEDENCE,
                RcaEvidenceType.DEPENDENCY
        );

        // Rank 2: API Gateway (investigated resource itself)
        RcaCandidateResponse rank2 = response.candidates().get(1);
        assertThat(rank2.rank()).isEqualTo(2);
        assertThat(rank2.candidateResourceId()).isEqualTo(apiResourceId);
        assertThat(rank2.evidenceScore()).isEqualTo(0.40); // ANOMALY only
        assertThat(rank2.primaryCandidate()).isFalse();

        // Rank 3: Redis (dependency only, no anomaly)
        RcaCandidateResponse rank3 = response.candidates().get(2);
        assertThat(rank3.rank()).isEqualTo(3);
        assertThat(rank3.candidateResourceId()).isEqualTo(redisResourceId);
        assertThat(rank3.evidenceScore()).isEqualTo(0.20); // DEPENDENCY only
        assertThat(rank3.primaryCandidate()).isFalse();
    }

    @Test
    @DisplayName("Test 1 & 14: Analyze incident with no evidence -> INSUFFICIENT_EVIDENCE")
    void testAnalyzeIncidentWithNoEvidence() {
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId)
                .resourceId(apiResourceId)
                .title("Spurious incident")
                .detectedAt(Instant.now())
                .build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        assertThat(response.status()).isEqualTo(RcaAnalysisStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.confidence()).isEqualTo(0.0);
        assertThat(response.confidenceLevel()).isEqualTo("LOW");
        assertThat(response.summary()).contains("No sufficiently supported root-cause candidate was identified");
        assertThat(response.candidates()).isEmpty();
    }

    @Test
    @DisplayName("Test 2: Analyze incident with incident-resource anomaly only")
    void testAnalyzeIncidentWithIncidentResourceAnomalyOnly() {
        Instant now = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(now).build();

        IncidentAnomalyEvidenceEntity evidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).incidentId(incidentId).resourceId(apiResourceId)
                .metricName("cpu_usage").observedValue(95.0).anomalyScore(0.85).observedAt(now).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(List.of(evidence));
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        assertThat(response.status()).isEqualTo(RcaAnalysisStatus.COMPLETED);
        assertThat(response.confidence()).isEqualTo(0.40);
        assertThat(response.candidates()).hasSize(1);
        assertThat(response.candidates().get(0).primaryCandidate()).isTrue();
        assertThat(response.candidates().get(0).evidenceScore()).isEqualTo(0.40);
        assertThat(response.candidates().get(0).explanation()).contains("internal candidate cause");
    }

    @Test
    @DisplayName("Test 4: Dependency anomaly AFTER incident anomaly does not receive temporal precedence")
    void testDependencyAnomalyAfterIncidentAnomaly() {
        Instant apiTime = Instant.parse("2026-09-24T14:30:00Z");
        Instant postgresLaterTime = Instant.parse("2026-09-24T14:31:00Z"); // AFTER api incident

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(apiTime).build();

        IncidentAnomalyEvidenceEntity apiEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).incidentId(incidentId).resourceId(apiResourceId)
                .metricName("latency").observedValue(500.0).anomalyScore(0.8).observedAt(apiTime).build();

        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(postgresResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();

        IncidentAnomalyEvidenceEntity postgresAnomaly = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(postgresResourceId).metricName("connections")
                .observedValue(90.0).anomalyScore(0.8).observedAt(postgresLaterTime).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(postgresResourceId)).thenReturn(Optional.of(postgresResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(List.of(apiEvidence));
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(postgresResourceId), any(Instant.class), any(Instant.class))).thenReturn(List.of(postgresAnomaly));

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        // PostgreSQL gets ANOMALY (0.40) + DEPENDENCY (0.20) = 0.60. NO TEMPORAL_PRECEDENCE (0.25).
        RcaCandidateResponse postgresCandidate = response.candidates().stream()
                .filter(c -> c.candidateResourceId().equals(postgresResourceId)).findFirst().orElseThrow();
        assertThat(postgresCandidate.evidenceScore()).isEqualTo(0.60);
        assertThat(postgresCandidate.explanation()).contains("did not precede");
    }

    @Test
    @DisplayName("Test 12: Direct dependents handling")
    void testDirectDependentsHandling() {
        Instant apiTime = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(apiTime).build();

        // Frontend depends on API (incoming dependent)
        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(frontendResourceId).targetResourceId(apiResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();

        IncidentAnomalyEvidenceEntity feAnomaly = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(frontendResourceId).metricName("http_5xx")
                .observedValue(50.0).anomalyScore(0.7).observedAt(apiTime).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(frontendResourceId)).thenReturn(Optional.of(frontendResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep));
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(frontendResourceId), any(Instant.class), any(Instant.class))).thenReturn(List.of(feAnomaly));

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        RcaCandidateResponse feCandidate = response.candidates().get(0);
        assertThat(feCandidate.candidateResourceId()).isEqualTo(frontendResourceId);
        assertThat(feCandidate.explanation()).contains("downstream dependent of api-gateway");
    }

    @Test
    @DisplayName("Test 13: Unrelated resources are strictly excluded")
    void testUnrelatedResourcesExcluded() {
        Instant now = Instant.now();
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(now).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        // Verify unrelatedResourceId was never evaluated or included
        assertThat(response.candidates().stream().anyMatch(c -> c.candidateResourceId().equals(unrelatedResourceId))).isFalse();
    }

    @Test
    @DisplayName("Test 18: Unknown incident throws ResourceNotFoundException (404)")
    void testUnknownIncidentThrows404() {
        UUID unknownId = UUID.randomUUID();
        when(incidentRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyzeIncident(unknownId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incident not found with ID: " + unknownId);
    }

    @Test
    @DisplayName("Test 19: Repeated analysis creates new analysis records deterministically")
    void testRepeatedAnalysisCreatesNewRecord() {
        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(Instant.now()).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        mockSaveAnalysis();

        service.analyzeIncident(incidentId);
        service.analyzeIncident(incidentId);

        // Verify analysis was saved twice
        ArgumentCaptor<RcaAnalysisEntity> captor = ArgumentCaptor.forClass(RcaAnalysisEntity.class);
        verify(analysisRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).hasSize(2);
    }

    @Test
    @DisplayName("Test getLatestAnalysisByIncidentId returns latest analysis or throws 404")
    void testGetLatestAnalysis() {
        when(incidentRepository.existsById(incidentId)).thenReturn(true);

        RcaAnalysisEntity analysis = RcaAnalysisEntity.builder()
                .id(UUID.randomUUID()).incidentId(incidentId).status(RcaAnalysisStatus.COMPLETED)
                .investigatedResourceId(apiResourceId).summary("Summary").confidence(0.85)
                .startedAt(Instant.now()).completedAt(Instant.now()).createdAt(Instant.now()).build();
        when(analysisRepository.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId)).thenReturn(Optional.of(analysis));
        when(candidateRepository.findByAnalysisIdOrderByRankAsc(analysis.getId())).thenReturn(Collections.emptyList());

        RcaAnalysisResponse res = service.getLatestAnalysisByIncidentId(incidentId);
        assertThat(res.id()).isEqualTo(analysis.getId());
        assertThat(res.confidence()).isEqualTo(0.85);

        // Missing incident -> 404
        UUID unknownId = UUID.randomUUID();
        when(incidentRepository.existsById(unknownId)).thenReturn(false);
        assertThatThrownBy(() -> service.getLatestAnalysisByIncidentId(unknownId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Ranking: When evidence scores tie, closer temporal precedence (delta ASC) ranks first")
    void testRankingTieBreakWithCloserTemporalPrecedence() {
        Instant incidentTime = Instant.parse("2026-09-24T14:32:00Z");
        Instant candidateATime = Instant.parse("2026-09-24T14:31:55Z"); // delta = 5s
        Instant candidateBTime = Instant.parse("2026-09-24T14:30:00Z"); // delta = 120s

        UUID resourceAId = UUID.randomUUID();
        UUID resourceBId = UUID.randomUUID();

        ResourceEntity resA = ResourceEntity.builder().id(resourceAId).name("service-a").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();
        ResourceEntity resB = ResourceEntity.builder().id(resourceBId).name("service-b").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(resourceAId)).thenReturn(Optional.of(resA));
        when(resourceRepository.findById(resourceBId)).thenReturn(Optional.of(resB));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());

        ResourceDependencyEntity depA = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(resourceAId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        ResourceDependencyEntity depB = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(resourceBId)
                .dependencyType(DependencyType.DEPENDS_ON).build();

        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(depA, depB));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        IncidentAnomalyEvidenceEntity anomalyA = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(resourceAId).metricName("metric_a")
                .observedValue(90.0).anomalyScore(0.85).observedAt(candidateATime).build();
        IncidentAnomalyEvidenceEntity anomalyB = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(resourceBId).metricName("metric_b")
                .observedValue(90.0).anomalyScore(0.85).observedAt(candidateBTime).build();

        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(resourceAId), any(Instant.class), any(Instant.class))).thenReturn(List.of(anomalyA));
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(resourceBId), any(Instant.class), any(Instant.class))).thenReturn(List.of(anomalyB));

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        // Both have score 0.85 (ANOMALY 0.40 + TEMPORAL_PRECEDENCE 0.25 + DEPENDENCY 0.20)
        // Candidate A (delta = 5s) MUST rank 1, Candidate B (delta = 120s) MUST rank 2
        assertThat(response.candidates()).hasSizeGreaterThanOrEqualTo(2);
        RcaCandidateResponse rank1 = response.candidates().get(0);
        RcaCandidateResponse rank2 = response.candidates().get(1);

        assertThat(rank1.candidateResourceId()).isEqualTo(resourceAId);
        assertThat(rank1.evidenceScore()).isEqualTo(0.85);
        assertThat(rank1.rank()).isEqualTo(1);
        assertThat(rank1.primaryCandidate()).isTrue();

        assertThat(rank2.candidateResourceId()).isEqualTo(resourceBId);
        assertThat(rank2.evidenceScore()).isEqualTo(0.85);
        assertThat(rank2.rank()).isEqualTo(2);
        assertThat(rank2.primaryCandidate()).isFalse();
    }

    @Test
    @DisplayName("Ranking: Higher evidence score always ranks above lower score regardless of temporal delta")
    void testRankingHigherScoreBeatsLowerScoreRegardlessOfDelta() {
        Instant incidentTime = Instant.parse("2026-09-24T14:32:00Z");
        Instant candidateATime = Instant.parse("2026-09-24T14:30:00Z"); // delta = 120s (farther)
        Instant candidateBTime = Instant.parse("2026-09-24T14:31:55Z"); // delta = 5s (closer)

        UUID resourceAId = UUID.randomUUID();
        UUID resourceBId = UUID.randomUUID();

        ResourceEntity resA = ResourceEntity.builder().id(resourceAId).name("service-a").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();
        ResourceEntity resB = ResourceEntity.builder().id(resourceBId).name("service-b").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(resourceAId)).thenReturn(Optional.of(resA));
        when(resourceRepository.findById(resourceBId)).thenReturn(Optional.of(resB));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());

        ResourceDependencyEntity depA = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(resourceAId)
                .dependencyType(DependencyType.DEPENDS_ON).build();

        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(depA));
        ResourceDependencyEntity depB = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(resourceBId).targetResourceId(apiResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(depB));

        IncidentAnomalyEvidenceEntity anomalyA = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(resourceAId).metricName("metric_a")
                .observedValue(90.0).anomalyScore(0.85).observedAt(candidateATime).build();
        IncidentAnomalyEvidenceEntity anomalyB = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(resourceBId).metricName("metric_b")
                .observedValue(90.0).anomalyScore(0.85).observedAt(candidateBTime).build();

        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(resourceAId), any(Instant.class), any(Instant.class))).thenReturn(List.of(anomalyA));
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(resourceBId), any(Instant.class), any(Instant.class))).thenReturn(List.of(anomalyB));

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        // Candidate A has score 0.85 (delta 120s)
        // Candidate B has score 0.65 (delta 5s)
        // Candidate A MUST rank 1 because score 0.85 > 0.65 despite larger delta!
        assertThat(response.candidates()).hasSizeGreaterThanOrEqualTo(2);
        RcaCandidateResponse rank1 = response.candidates().get(0);
        RcaCandidateResponse rank2 = response.candidates().get(1);

        assertThat(rank1.candidateResourceId()).isEqualTo(resourceAId);
        assertThat(rank1.evidenceScore()).isEqualTo(0.85);
        assertThat(rank1.rank()).isEqualTo(1);

        assertThat(rank2.candidateResourceId()).isEqualTo(resourceBId);
        assertThat(rank2.evidenceScore()).isEqualTo(0.65);
        assertThat(rank2.rank()).isEqualTo(2);
    }

    @Test
    @DisplayName("Ranking: When both score and temporal delta tie, candidateResourceId ASC is final tie-breaker")
    void testRankingCandidateResourceIdTieBreak() {
        Instant incidentTime = Instant.parse("2026-09-24T14:32:00Z");
        Instant anomalyTime = Instant.parse("2026-09-24T14:31:55Z"); // both delta = 5s

        UUID id1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID id2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

        ResourceEntity res1 = ResourceEntity.builder().id(id1).name("service-1").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();
        ResourceEntity res2 = ResourceEntity.builder().id(id2).name("service-2").type(ResourceType.SERVICE).status(ResourceStatus.HEALTHY).build();

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId).resourceId(apiResourceId).detectedAt(incidentTime).build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(id1)).thenReturn(Optional.of(res1));
        when(resourceRepository.findById(id2)).thenReturn(Optional.of(res2));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(Collections.emptyList());

        ResourceDependencyEntity dep1 = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(id1)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        ResourceDependencyEntity dep2 = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(id2)
                .dependencyType(DependencyType.DEPENDS_ON).build();

        // Pass them in reverse order (id2 first) to ensure sorting actually changes the order
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(List.of(dep2, dep1));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId)).thenReturn(Collections.emptyList());

        IncidentAnomalyEvidenceEntity anom1 = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(id1).metricName("metric_1")
                .observedValue(90.0).anomalyScore(0.85).observedAt(anomalyTime).build();
        IncidentAnomalyEvidenceEntity anom2 = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(id2).metricName("metric_2")
                .observedValue(90.0).anomalyScore(0.85).observedAt(anomalyTime).build();

        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(id1), any(Instant.class), any(Instant.class))).thenReturn(List.of(anom1));
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(id2), any(Instant.class), any(Instant.class))).thenReturn(List.of(anom2));

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(incidentId);

        // Both have score 0.85 and delta 5s. id1 ("1111...") < id2 ("2222..."), so id1 ranks 1, id2 ranks 2
        assertThat(response.candidates()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.candidates().get(0).candidateResourceId()).isEqualTo(id1);
        assertThat(response.candidates().get(0).rank()).isEqualTo(1);
        assertThat(response.candidates().get(1).candidateResourceId()).isEqualTo(id2);
        assertThat(response.candidates().get(1).rank()).isEqualTo(2);
    }

    @Test
    @DisplayName("Part 23: Deterministic Golden Test — Multiple runs on identical input produce identical output")
    void testPart23DeterministicGoldenScenarioMultiRun() {
        Instant apiTime = Instant.parse("2026-09-24T14:32:05Z");
        Instant postgresTime = Instant.parse("2026-09-24T14:30:42Z"); // 83 seconds before

        IncidentEntity incident = IncidentEntity.builder()
                .id(incidentId)
                .resourceId(apiResourceId)
                .title("API latency anomaly")
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.DETECTED)
                .detectedAt(apiTime)
                .build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(resourceRepository.findById(postgresResourceId)).thenReturn(Optional.of(postgresResource));
        when(resourceRepository.findById(redisResourceId)).thenReturn(Optional.of(redisResource));
        when(resourceRepository.findById(frontendResourceId)).thenReturn(Optional.of(frontendResource));

        // API anomaly evidence
        IncidentAnomalyEvidenceEntity apiEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .incidentId(incidentId)
                .resourceId(apiResourceId)
                .metricName("api_response_time")
                .observedValue(850.0)
                .anomalyScore(0.92)
                .observedAt(apiTime)
                .build();
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId))
                .thenReturn(List.of(apiEvidence));

        // Topology: frontend -> api -> postgres; api -> redis
        ResourceDependencyEntity depPostgres = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(postgresResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        ResourceDependencyEntity depRedis = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(apiResourceId).targetResourceId(redisResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(apiResourceId))
                .thenReturn(List.of(depPostgres, depRedis));

        // Frontend depends on API (incoming dependent)
        ResourceDependencyEntity depFrontend = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(frontendResourceId).targetResourceId(apiResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(apiResourceId))
                .thenReturn(List.of(depFrontend));

        // PostgreSQL anomaly at 14:30:42Z
        IncidentAnomalyEvidenceEntity postgresAnomaly = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .resourceId(postgresResourceId)
                .metricName("db_connection_utilization")
                .observedValue(97.0)
                .anomalyScore(0.86)
                .observedAt(postgresTime)
                .build();
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(postgresResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(postgresAnomaly));

        // Redis has no anomaly (dependency only)
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(redisResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        // Frontend has no anomaly
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(frontendResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        mockSaveAnalysis();

        // Execute multiple repeated RCA runs on identical input state (10 runs)
        int runs = 10;
        List<RcaAnalysisResponse> responses = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            responses.add(service.analyzeIncident(incidentId));
        }

        RcaAnalysisResponse baseline = responses.get(0);
        assertThat(baseline.status()).isEqualTo(RcaAnalysisStatus.COMPLETED);
        assertThat(baseline.confidence()).isEqualTo(0.85);
        assertThat(baseline.confidenceLevel()).isEqualTo("VERY_HIGH");
        assertThat(baseline.candidates()).hasSize(3);

        for (int runIdx = 1; runIdx < runs; runIdx++) {
            RcaAnalysisResponse current = responses.get(runIdx);

            assertThat(current.status()).isEqualTo(baseline.status());
            assertThat(current.confidence()).isEqualTo(baseline.confidence());
            assertThat(current.confidenceLevel()).isEqualTo(baseline.confidenceLevel());
            assertThat(current.summary()).isEqualTo(baseline.summary());
            assertThat(current.candidates()).hasSameSizeAs(baseline.candidates());

            for (int cIdx = 0; cIdx < baseline.candidates().size(); cIdx++) {
                RcaCandidateResponse baseCand = baseline.candidates().get(cIdx);
                RcaCandidateResponse currCand = current.candidates().get(cIdx);

                assertThat(currCand.candidateResourceId()).isEqualTo(baseCand.candidateResourceId());
                assertThat(currCand.rank()).isEqualTo(baseCand.rank());
                assertThat(currCand.evidenceScore()).isEqualTo(baseCand.evidenceScore());
                assertThat(currCand.primaryCandidate()).isEqualTo(baseCand.primaryCandidate());
                assertThat(currCand.candidateMetric()).isEqualTo(baseCand.candidateMetric());
                assertThat(currCand.candidateCause()).isEqualTo(baseCand.candidateCause());
                assertThat(currCand.explanation()).isEqualTo(baseCand.explanation());

                assertThat(currCand.evidence()).hasSameSizeAs(baseCand.evidence());
                for (int eIdx = 0; eIdx < baseCand.evidence().size(); eIdx++) {
                    RcaEvidenceResponse baseEv = baseCand.evidence().get(eIdx);
                    RcaEvidenceResponse currEv = currCand.evidence().get(eIdx);

                    assertThat(currEv.evidenceType()).isEqualTo(baseEv.evidenceType());
                    assertThat(currEv.contributionScore()).isEqualTo(baseEv.contributionScore());
                    assertThat(currEv.explanation()).isEqualTo(baseEv.explanation());
                }
            }
        }
    }

    @Test
    @DisplayName("Section 10: Strict 1-hop topological radius — Investigating frontend does NOT include 2-hop postgres")
    void testRcaStrict1HopTopologicalRadiusDoesNotIncludeSecondHop() {
        // Topology: frontend -> api -> postgres
        // Investigated resource: frontend
        UUID frontendIncidentId = UUID.randomUUID();
        Instant now = Instant.now();

        IncidentEntity frontendIncident = IncidentEntity.builder()
                .id(frontendIncidentId)
                .resourceId(frontendResourceId)
                .title("Frontend error rate")
                .detectedAt(now)
                .build();

        when(incidentRepository.findById(frontendIncidentId)).thenReturn(Optional.of(frontendIncident));
        when(resourceRepository.findById(frontendResourceId)).thenReturn(Optional.of(frontendResource));
        when(resourceRepository.findById(apiResourceId)).thenReturn(Optional.of(apiResource));
        when(incidentEvidenceRepository.findByIncidentIdOrderByObservedAtAsc(frontendIncidentId))
                .thenReturn(Collections.emptyList());

        // Frontend only directly depends on API
        ResourceDependencyEntity depApi = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(frontendResourceId).targetResourceId(apiResourceId)
                .dependencyType(DependencyType.DEPENDS_ON).build();
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(frontendResourceId))
                .thenReturn(List.of(depApi));
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(frontendResourceId))
                .thenReturn(Collections.emptyList());

        // API has an anomaly
        IncidentAnomalyEvidenceEntity apiAnomaly = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID()).resourceId(apiResourceId).metricName("http_5xx")
                .observedValue(12.0).anomalyScore(0.85).observedAt(now.minusSeconds(10)).build();
        when(incidentEvidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(
                eq(apiResourceId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(apiAnomaly));

        mockSaveAnalysis();

        RcaAnalysisResponse response = service.analyzeIncident(frontendIncidentId);

        // Verification: Candidates MUST ONLY contain frontend and direct dependency API.
        // PostgreSQL (which is 2 hops away) MUST NOT be present!
        List<UUID> candidateIds = response.candidates().stream()
                .map(RcaCandidateResponse::candidateResourceId)
                .toList();

        assertThat(candidateIds)
                .contains(apiResourceId)
                .doesNotContain(postgresResourceId);
    }
}
