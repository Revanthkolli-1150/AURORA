package com.aurora.platform.intelligence.historical.infrastructure.adapter;

import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import com.aurora.platform.intelligence.rca.entity.RcaCandidateEntity;
import com.aurora.platform.intelligence.rca.repository.RcaAnalysisRepository;
import com.aurora.platform.intelligence.rca.repository.RcaCandidateRepository;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalIncidentQueryAdapterTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentAnomalyEvidenceRepository evidenceRepository;

    @Mock
    private ResourceRepository resourceRepository;

    @Mock
    private ResourceDependencyRepository dependencyRepository;

    @Mock
    private RcaAnalysisRepository rcaAnalysisRepository;

    @Mock
    private RcaCandidateRepository rcaCandidateRepository;

    private HistoricalIncidentQueryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HistoricalIncidentQueryAdapter(
                incidentRepository,
                evidenceRepository,
                resourceRepository,
                dependencyRepository,
                rcaAnalysisRepository,
                rcaCandidateRepository
        );
    }

    @Test
    @DisplayName("B.1, B.2, B.3: Metrics are sorted, duplicates removed, and canonicalized")
    void shouldExtractSortedUniqueMetrics() {
        UUID incId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();

        IncidentEntity incident = createIncident(incId, resId, "test-incident", IncidentSeverity.HIGH, IncidentStatus.RESOLVED, "disk_usage");
        ResourceEntity resource = createResource(resId, "app-srv", ResourceType.APPLICATION);

        // Multiple evidence items with duplicate names and non-sorted insertion order
        List<IncidentAnomalyEvidenceEntity> evidence = List.of(
                createEvidence(incId, resId, "z_metric"),
                createEvidence(incId, resId, "a_metric"),
                createEvidence(incId, resId, "m_metric"),
                createEvidence(incId, resId, "a_metric") // duplicate
        );

        when(incidentRepository.findById(incId)).thenReturn(Optional.of(incident));
        when(evidenceRepository.findByIncidentIdIn(any())).thenReturn(evidence);
        when(dependencyRepository.findBySourceResourceIdIn(any())).thenReturn(List.of());
        when(dependencyRepository.findByTargetResourceIdIn(any())).thenReturn(List.of());
        when(resourceRepository.findAllById(any())).thenReturn(List.of(resource));
        when(rcaAnalysisRepository.findByIncidentIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());

        Optional<IncidentSignature> signatureOpt = adapter.findSignature(incId);
        assertThat(signatureOpt).isPresent();

        IncidentSignature sig = signatureOpt.get();
        // TreeSet guarantees sorted unique order
        assertThat(sig.anomalousMetrics()).containsExactly("a_metric", "m_metric", "z_metric");
    }

    @Test
    @DisplayName("B.4 - B.8: 1-hop topology tokens (UPSTREAM & DOWNSTREAM), unique, sorted, no 2-hop")
    void shouldExtractOneHopTopologyTokens() {
        UUID incId = UUID.randomUUID();
        UUID targetResId = UUID.randomUUID();
        UUID upstreamResId = UUID.randomUUID();
        UUID downstreamResId = UUID.randomUUID();

        IncidentEntity incident = createIncident(incId, targetResId, "topo-test", IncidentSeverity.HIGH, IncidentStatus.RESOLVED, null);

        ResourceEntity targetResource = createResource(targetResId, "service-b", ResourceType.SERVICE);
        ResourceEntity upstreamResource = createResource(upstreamResId, "database-c", ResourceType.DATABASE);
        ResourceEntity downstreamResource = createResource(downstreamResId, "server-a", ResourceType.SERVER);

        // target depends on upstream -> target -> upstream (UPSTREAM:DATABASE)
        ResourceDependencyEntity outgoing = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID())
                .sourceResourceId(targetResId)
                .targetResourceId(upstreamResId)
                .dependencyType(DependencyType.DEPENDS_ON)
                .createdAt(Instant.now())
                .build();

        // downstream depends on target -> downstream -> target (DOWNSTREAM:SERVER)
        ResourceDependencyEntity incoming = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID())
                .sourceResourceId(downstreamResId)
                .targetResourceId(targetResId)
                .dependencyType(DependencyType.DEPENDS_ON)
                .createdAt(Instant.now())
                .build();

        when(incidentRepository.findById(incId)).thenReturn(Optional.of(incident));
        when(evidenceRepository.findByIncidentIdIn(any())).thenReturn(List.of());
        when(dependencyRepository.findBySourceResourceIdIn(any())).thenReturn(List.of(outgoing));
        when(dependencyRepository.findByTargetResourceIdIn(any())).thenReturn(List.of(incoming));
        when(resourceRepository.findAllById(any())).thenReturn(List.of(targetResource, upstreamResource, downstreamResource));
        when(rcaAnalysisRepository.findByIncidentIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());

        Optional<IncidentSignature> signatureOpt = adapter.findSignature(incId);
        assertThat(signatureOpt).isPresent();

        Set<String> tokens = signatureOpt.get().topologyTokens();
        // Canonical sorted tokens
        assertThat(tokens).containsExactly("DOWNSTREAM:SERVER", "UPSTREAM:DATABASE");
    }

    @Test
    @DisplayName("B.9: Missing RCA produces null primary RCA context")
    void shouldHandleMissingRcaGracefully() {
        UUID incId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();

        IncidentEntity incident = createIncident(incId, resId, "no-rca", IncidentSeverity.MEDIUM, IncidentStatus.RESOLVED, null);
        ResourceEntity res = createResource(resId, "service-x", ResourceType.SERVICE);

        when(incidentRepository.findById(incId)).thenReturn(Optional.of(incident));
        when(evidenceRepository.findByIncidentIdIn(any())).thenReturn(List.of());
        when(dependencyRepository.findBySourceResourceIdIn(any())).thenReturn(List.of());
        when(dependencyRepository.findByTargetResourceIdIn(any())).thenReturn(List.of());
        when(resourceRepository.findAllById(any())).thenReturn(List.of(res));
        when(rcaAnalysisRepository.findByIncidentIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());

        Optional<IncidentSignature> sig = adapter.findSignature(incId);
        assertThat(sig).isPresent();
        assertThat(sig.get().primaryRcaCause()).isNull();
    }

    @Test
    @DisplayName("E.1 - E.5: Resolved-only, self-exclusion, 200 bound, and O(1) batch queries (No N+1)")
    void shouldExecuteBatchQueriesWithoutNPlusOne() {
        UUID excludedId = UUID.randomUUID();
        List<IncidentEntity> candidates = new ArrayList<>();

        for (int i = 0; i < 50; i++) {
            UUID cid = UUID.randomUUID();
            UUID rid = UUID.randomUUID();
            candidates.add(createIncident(
                    cid, rid, "inc-" + i, IncidentSeverity.MEDIUM, IncidentStatus.RESOLVED, "cause-" + i
            ));
        }

        when(incidentRepository.findByStatusAndIdNotOrderByResolvedAtDescIdAsc(
                eq(IncidentStatus.RESOLVED), eq(excludedId), any(Pageable.class)
        )).thenReturn(candidates);

        when(evidenceRepository.findByIncidentIdIn(any())).thenReturn(List.of());
        when(dependencyRepository.findBySourceResourceIdIn(any())).thenReturn(List.of());
        when(dependencyRepository.findByTargetResourceIdIn(any())).thenReturn(List.of());
        when(resourceRepository.findAllById(any())).thenReturn(List.of());
        when(rcaAnalysisRepository.findByIncidentIdInOrderByCreatedAtDesc(any())).thenReturn(List.of());

        List<IncidentSignature> signatures = adapter.findCandidateCorpusSignatures(excludedId, 200);

        assertThat(signatures).hasSize(50);

        // Verify that exactly 1 call was made to each batch method across all 50 candidates (NO N+1)
        verify(evidenceRepository, times(1)).findByIncidentIdIn(any());
        verify(dependencyRepository, times(1)).findBySourceResourceIdIn(any());
        verify(dependencyRepository, times(1)).findByTargetResourceIdIn(any());
        verify(resourceRepository, times(1)).findAllById(any());
        verify(rcaAnalysisRepository, times(1)).findByIncidentIdInOrderByCreatedAtDesc(any());

        // Verify Pageable limit passed to repository is bounded at 200
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findByStatusAndIdNotOrderByResolvedAtDescIdAsc(
                eq(IncidentStatus.RESOLVED), eq(excludedId), pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("E.6: Bound enforcement caps database limit at exactly 200 when requested candidates > 200")
    void shouldCapCandidateCorpusQueryAt200EvenWhenCallerRequestsMore() {
        UUID excludedId = UUID.randomUUID();
        when(incidentRepository.findByStatusAndIdNotOrderByResolvedAtDescIdAsc(
                eq(IncidentStatus.RESOLVED), eq(excludedId), any(Pageable.class)
        )).thenReturn(List.of());

        adapter.findCandidateCorpusSignatures(excludedId, 500);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(incidentRepository).findByStatusAndIdNotOrderByResolvedAtDescIdAsc(
                eq(IncidentStatus.RESOLVED), eq(excludedId), pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(200);
    }

    private IncidentEntity createIncident(UUID id, UUID resourceId, String title, IncidentSeverity severity, IncidentStatus status, String rootCause) {
        IncidentEntity entity = new IncidentEntity();
        entity.setId(id);
        entity.setResourceId(resourceId);
        entity.setTitle(title);
        entity.setSeverity(severity);
        entity.setStatus(status);
        entity.setRootCause(rootCause);
        entity.setDetectedAt(Instant.now().minusSeconds(500));
        entity.setResolvedAt(Instant.now().minusSeconds(100));
        entity.setCreatedAt(Instant.now().minusSeconds(500));
        entity.setUpdatedAt(Instant.now().minusSeconds(100));
        return entity;
    }

    private ResourceEntity createResource(UUID id, String name, ResourceType type) {
        return ResourceEntity.builder()
                .id(id)
                .name(name)
                .type(type)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("host-1")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private IncidentAnomalyEvidenceEntity createEvidence(UUID incidentId, UUID resourceId, String metricName) {
        return IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .incidentId(incidentId)
                .resourceId(resourceId)
                .metricName(metricName)
                .observedValue(99.0)
                .anomalyScore(0.95)
                .zScore(4.5)
                .detectionMethod("Z_SCORE")
                .observedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
    }
}
