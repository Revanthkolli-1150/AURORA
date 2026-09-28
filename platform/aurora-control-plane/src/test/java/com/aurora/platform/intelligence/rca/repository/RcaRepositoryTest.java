package com.aurora.platform.intelligence.rca.repository;

import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.entity.RcaCandidateEntity;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceEntity;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceType;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class RcaRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private RcaAnalysisRepository analysisRepository;

    @Autowired
    private RcaCandidateRepository candidateRepository;

    @Autowired
    private RcaEvidenceRepository evidenceRepository;

    private ResourceEntity apiResource;
    private ResourceEntity postgresResource;
    private IncidentEntity incident;

    @BeforeEach
    void setUp() {
        apiResource = ResourceEntity.builder()
                .name("api-service")
                .type(ResourceType.SERVICE)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("api.aurora.internal")
                .build();
        apiResource = resourceRepository.save(apiResource);

        postgresResource = ResourceEntity.builder()
                .name("postgres-db")
                .type(ResourceType.DATABASE)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("db.aurora.internal")
                .build();
        postgresResource = resourceRepository.save(postgresResource);

        incident = IncidentEntity.builder()
                .resourceId(apiResource.getId())
                .title("API Latency Degradation")
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.DETECTED)
                .detectedAt(Instant.now())
                .build();
        incident = incidentRepository.save(incident);

        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("Persist and retrieve RCA analysis, candidates, and evidence with deterministic ordering")
    void testPersistAndRetrieveRcaGraph() {
        Instant now = Instant.now();

        RcaAnalysisEntity analysis = RcaAnalysisEntity.builder()
                .incidentId(incident.getId())
                .investigatedResourceId(apiResource.getId())
                .status(RcaAnalysisStatus.COMPLETED)
                .summary("RCA completed")
                .confidence(0.85)
                .startedAt(now.minusSeconds(10))
                .completedAt(now)
                .createdAt(now)
                .build();
        analysis = analysisRepository.save(analysis);

        RcaCandidateEntity candidate1 = RcaCandidateEntity.builder()
                .analysisId(analysis.getId())
                .candidateResourceId(postgresResource.getId())
                .candidateMetric("db_connections")
                .candidateCause("Database connection pool exhaustion")
                .evidenceScore(0.85)
                .rank(1)
                .explanation("Preceded API anomaly")
                .primaryCandidate(true)
                .createdAt(now)
                .build();
        candidate1 = candidateRepository.save(candidate1);

        RcaEvidenceEntity evidence1 = RcaEvidenceEntity.builder()
                .candidateId(candidate1.getId())
                .evidenceType(RcaEvidenceType.ANOMALY)
                .resourceId(postgresResource.getId())
                .metricName("db_connections")
                .observedValue(95.0)
                .anomalyScore(0.9)
                .observedAt(now.minusSeconds(80))
                .contributionScore(0.40)
                .explanation("Exhibited anomaly")
                .createdAt(now)
                .build();
        evidenceRepository.save(evidence1);

        entityManager.flush();
        entityManager.clear();

        // Retrieve analysis
        List<RcaAnalysisEntity> analyses = analysisRepository.findByIncidentIdOrderByCreatedAtDesc(incident.getId());
        assertThat(analyses).hasSize(1);
        assertThat(analyses.get(0).getConfidence()).isEqualTo(0.85);

        // Retrieve candidates by rank
        List<RcaCandidateEntity> candidates = candidateRepository.findByAnalysisIdOrderByRankAsc(analysis.getId());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getRank()).isEqualTo(1);
        assertThat(candidates.get(0).getPrimaryCandidate()).isTrue();

        // Retrieve evidence by contribution score
        List<RcaEvidenceEntity> evidence = evidenceRepository.findByCandidateIdOrderByContributionScoreDesc(candidate1.getId());
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).getEvidenceType()).isEqualTo(RcaEvidenceType.ANOMALY);
    }
}
