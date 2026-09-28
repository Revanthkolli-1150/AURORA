package com.aurora.platform.intelligence.narrative.repository;

import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.narrative.domain.IncidentNarrativeEntity;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.repository.RcaAnalysisRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class IncidentNarrativeRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private RcaAnalysisRepository analysisRepository;

    @Autowired
    private IncidentNarrativeRepository narrativeRepository;

    private IncidentEntity incident;
    private RcaAnalysisEntity analysis1;
    private RcaAnalysisEntity analysis2;

    @BeforeEach
    void setUp() {
        ResourceEntity resource = ResourceEntity.builder()
                .name("order-service")
                .type(ResourceType.SERVICE)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("order.internal")
                .build();
        resource = resourceRepository.save(resource);

        incident = IncidentEntity.builder()
                .resourceId(resource.getId())
                .title("Order Service Latency Surge")
                .description("Latencies exceeded 2000ms threshold")
                .severity(IncidentSeverity.CRITICAL)
                .status(IncidentStatus.INVESTIGATING)
                .confidence(0.95)
                .rootCause("Database connection pool exhaustion")
                .detectedAt(Instant.now().minusSeconds(300))
                .build();
        incident = incidentRepository.save(incident);

        analysis1 = RcaAnalysisEntity.builder()
                .incidentId(incident.getId())
                .investigatedResourceId(resource.getId())
                .status(RcaAnalysisStatus.COMPLETED)
                .summary("Deterministic RCA identifies db pool starvation.")
                .confidence(0.92)
                .startedAt(Instant.now().minusSeconds(150))
                .completedAt(Instant.now().minusSeconds(120))
                .build();
        analysis1 = analysisRepository.save(analysis1);

        analysis2 = RcaAnalysisEntity.builder()
                .incidentId(incident.getId())
                .investigatedResourceId(resource.getId())
                .status(RcaAnalysisStatus.COMPLETED)
                .summary("Updated RCA after graph re-scoring.")
                .confidence(0.94)
                .startedAt(Instant.now().minusSeconds(90))
                .completedAt(Instant.now().minusSeconds(60))
                .build();
        analysis2 = analysisRepository.save(analysis2);

        entityManager.flush();
    }

    @Test
    @DisplayName("Should persist narrative entity and reload fields intact")
    void shouldPersistAndRetrieveNarrative() {
        IncidentNarrativeEntity entity = IncidentNarrativeEntity.builder()
                .incidentId(incident.getId())
                .rcaAnalysisId(analysis1.getId())
                .headline("Order Latency Surge")
                .executiveSummary("Payment latency triggered downstream queue saturation.")
                .observedSymptoms("[\"Elevated 504 errors on /checkout\", \"Thread pool rejection\"]")
                .rootCauseExplanation("Postgres connection leak in order-service")
                .secondaryHypotheses("[\"Redis cache eviction surge\", \"GC pause spike\"]")
                .historicalContext("Matches INC-101 with 0.88 similarity.")
                .investigationSteps("[\"Inspect active pg connections\", \"Check pg_stat_activity\"]")
                .caveats("[\"Only 2 historical samples match this topology\"]")
                .provider("openai")
                .modelName("gpt-4o-mini")
                .promptVersion("operator-narrative-v1")
                .fallbackUsed(false)
                .generationDurationMs(230)
                .build();

        IncidentNarrativeEntity saved = narrativeRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        Optional<IncidentNarrativeEntity> found = narrativeRepository.findById(saved.getId());
        assertThat(found).isPresent();
        IncidentNarrativeEntity loaded = found.get();
        assertThat(loaded.getIncidentId()).isEqualTo(incident.getId());
        assertThat(loaded.getRcaAnalysisId()).isEqualTo(analysis1.getId());
        assertThat(loaded.getHeadline()).isEqualTo("Order Latency Surge");
        assertThat(loaded.getExecutiveSummary()).isEqualTo("Payment latency triggered downstream queue saturation.");
        assertThat(loaded.getObservedSymptoms()).contains("Elevated 504 errors on /checkout");
        assertThat(loaded.getRootCauseExplanation()).isEqualTo("Postgres connection leak in order-service");
        assertThat(loaded.getHistoricalContext()).isEqualTo("Matches INC-101 with 0.88 similarity.");
        assertThat(loaded.getProvider()).isEqualTo("openai");
        assertThat(loaded.getModelName()).isEqualTo("gpt-4o-mini");
        assertThat(loaded.getPromptVersion()).isEqualTo("operator-narrative-v1");
        assertThat(loaded.getFallbackUsed()).isFalse();
        assertThat(loaded.getGenerationDurationMs()).isEqualTo(230);
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should find narrative by rcaAnalysisId")
    void shouldFindByRcaAnalysisId() {
        IncidentNarrativeEntity entity = IncidentNarrativeEntity.builder()
                .incidentId(incident.getId())
                .rcaAnalysisId(analysis1.getId())
                .headline("Analysis 1 Headline")
                .executiveSummary("Deterministic analysis 1 summary")
                .observedSymptoms("[\"Symptom A\"]")
                .rootCauseExplanation("Cause A")
                .secondaryHypotheses("[]")
                .investigationSteps("[]")
                .caveats("[]")
                .provider("local-ollama")
                .modelName("llama3.1:8b")
                .promptVersion("operator-narrative-v1")
                .fallbackUsed(false)
                .generationDurationMs(150)
                .build();

        narrativeRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        Optional<IncidentNarrativeEntity> found = narrativeRepository.findByRcaAnalysisId(analysis1.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRcaAnalysisId()).isEqualTo(analysis1.getId());
        assertThat(found.get().getExecutiveSummary()).isEqualTo("Deterministic analysis 1 summary");
    }

    @Test
    @DisplayName("Should find latest narrative for incident ordered by createdAt DESC")
    void shouldFindTopByIncidentIdOrderByCreatedAtDesc() {
        IncidentNarrativeEntity narrative1 = IncidentNarrativeEntity.builder()
                .incidentId(incident.getId())
                .rcaAnalysisId(analysis1.getId())
                .headline("Headline 1")
                .executiveSummary("First narrative")
                .observedSymptoms("[\"Symptom 1\"]")
                .rootCauseExplanation("Cause 1")
                .secondaryHypotheses("[]")
                .investigationSteps("[]")
                .caveats("[]")
                .provider("openai")
                .modelName("gpt-4o")
                .promptVersion("operator-narrative-v1")
                .fallbackUsed(false)
                .generationDurationMs(200)
                .build();
        narrativeRepository.save(narrative1);
        entityManager.flush();

        IncidentNarrativeEntity narrative2 = IncidentNarrativeEntity.builder()
                .incidentId(incident.getId())
                .rcaAnalysisId(analysis2.getId())
                .headline("Headline 2")
                .executiveSummary("Second narrative")
                .observedSymptoms("[\"Symptom 2\"]")
                .rootCauseExplanation("Cause 2")
                .secondaryHypotheses("[]")
                .investigationSteps("[]")
                .caveats("[]")
                .provider("openai")
                .modelName("gpt-4o")
                .promptVersion("operator-narrative-v1")
                .fallbackUsed(false)
                .generationDurationMs(180)
                .build();
        narrativeRepository.save(narrative2);
        entityManager.flush();
        entityManager.clear();

        Optional<IncidentNarrativeEntity> latest = narrativeRepository.findTopByIncidentIdOrderByCreatedAtDesc(incident.getId());
        assertThat(latest).isPresent();
        assertThat(latest.get().getRcaAnalysisId()).isEqualTo(analysis2.getId());
        assertThat(latest.get().getExecutiveSummary()).isEqualTo("Second narrative");
    }

    @Test
    @DisplayName("Should reject duplicate narrative for the same rca_analysis_id (UQ constraint)")
    void shouldEnforceUniqueRcaAnalysisConstraint() {
        IncidentNarrativeEntity narrative1 = IncidentNarrativeEntity.builder()
                .incidentId(incident.getId())
                .rcaAnalysisId(analysis1.getId())
                .headline("Headline 1")
                .executiveSummary("Narrative 1")
                .observedSymptoms("[\"Symptom 1\"]")
                .rootCauseExplanation("Cause 1")
                .secondaryHypotheses("[]")
                .investigationSteps("[]")
                .caveats("[]")
                .provider("openai")
                .modelName("gpt-4o")
                .promptVersion("operator-narrative-v1")
                .fallbackUsed(false)
                .generationDurationMs(200)
                .build();
        narrativeRepository.save(narrative1);
        entityManager.flush();

        IncidentNarrativeEntity duplicate = IncidentNarrativeEntity.builder()
                .incidentId(incident.getId())
                .rcaAnalysisId(analysis1.getId()) // Same RCA Analysis ID
                .headline("Headline Duplicate")
                .executiveSummary("Duplicate narrative")
                .observedSymptoms("[\"Symptom 1b\"]")
                .rootCauseExplanation("Cause 1b")
                .secondaryHypotheses("[]")
                .investigationSteps("[]")
                .caveats("[]")
                .provider("openai")
                .modelName("gpt-4o")
                .promptVersion("operator-narrative-v1")
                .fallbackUsed(false)
                .generationDurationMs(190)
                .build();

        assertThatThrownBy(() -> {
            narrativeRepository.save(duplicate);
            entityManager.flush();
        }).satisfies(ex -> assertThat(ex).isInstanceOfAny(
                DataIntegrityViolationException.class,
                org.hibernate.exception.ConstraintViolationException.class,
                jakarta.persistence.PersistenceException.class
        ));
    }
}
