package com.aurora.platform;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.service.RecoveryService;
import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.service.TelemetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DatabaseMigrationIntegrationTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private TelemetryService telemetryService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private RecoveryService recoveryService;

    @Autowired
    private com.aurora.platform.incident.service.IncidentCorrelationService incidentCorrelationService;

    @Autowired
    private com.aurora.platform.dependency.service.ResourceDependencyService dependencyService;

    @Autowired
    private com.aurora.platform.intelligence.rca.service.RcaAnalysisService rcaAnalysisService;

    @Test
    @DisplayName("End-to-End lifecycle: Resource -> Telemetry -> Incident -> Recovery Plan")
    void testFullControlPlaneLifecycle() {
        // 1. Create Resource
        CreateResourceRequest resourceReq = new CreateResourceRequest(
                "billing-service-primary",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-billing-01.us-east",
                Map.of("region", "us-east-1", "version", "2.1.0")
        );
        ResourceResponse resource = resourceService.createResource(resourceReq);
        assertThat(resource.id()).isNotNull();
        assertThat(resource.name()).isEqualTo("billing-service-primary");

        // Verify retrieval
        ResourceResponse retrievedResource = resourceService.getResourceById(resource.id());
        assertThat(retrievedResource.id()).isEqualTo(resource.id());
        assertThat(retrievedResource.metadata()).containsEntry("region", "us-east-1");

        // 2. Ingest Telemetry
        IngestTelemetryRequest telemetryReq = new IngestTelemetryRequest(
                resource.id(),
                Instant.now(),
                TelemetryType.METRIC,
                "http.error_rate.percentage",
                12.4,
                "percent",
                Map.of("endpoint", "/checkout")
        );
        TelemetryEventResponse telemetry = telemetryService.ingestTelemetry(telemetryReq);
        assertThat(telemetry.id()).isNotNull();
        assertThat(telemetry.resourceId()).isEqualTo(resource.id());

        List<TelemetryEventResponse> events = telemetryService.getTelemetryByResourceId(resource.id());
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).metricName()).isEqualTo("http.error_rate.percentage");

        // 3. Create Incident
        CreateIncidentRequest incidentReq = new CreateIncidentRequest(
                resource.id(),
                "Checkout Error Rate Breach",
                "HTTP error rate exceeded 10% SLA threshold on billing service",
                IncidentSeverity.CRITICAL,
                IncidentStatus.DETECTED,
                0.96,
                "Payment gateway 504 Gateway Timeout responses",
                Instant.now()
        );
        IncidentResponse incident = incidentService.createIncident(incidentReq);
        assertThat(incident.id()).isNotNull();
        assertThat(incident.resourceId()).isEqualTo(resource.id());
        assertThat(incident.status()).isEqualTo(IncidentStatus.DETECTED);

        IncidentResponse retrievedIncident = incidentService.getIncidentById(incident.id());
        assertThat(retrievedIncident.title()).isEqualTo("Checkout Error Rate Breach");

        // 4. Create Recovery Plan with Action
        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .actionType("CIRCUIT_BREAKER_ENABLE")
                .target("billing-payment-gateway-client")
                .status(RecoveryActionStatus.PENDING)
                .build();

        RecoveryPlanResponse plan = recoveryService.createRecoveryPlan(
                incident.id(),
                "Enable circuit breaker to prevent cascading failures to upstream callers",
                0.93,
                RecoveryRisk.MEDIUM,
                true,
                List.of(action)
        );

        assertThat(plan.id()).isNotNull();
        assertThat(plan.incidentId()).isEqualTo(incident.id());
        assertThat(plan.actions()).hasSize(1);
        assertThat(plan.actions().get(0).actionType()).isEqualTo("CIRCUIT_BREAKER_ENABLE");

        // Verify retrieval of Recovery Plan
        RecoveryPlanResponse retrievedPlan = recoveryService.getRecoveryPlanByIncidentId(incident.id());
        assertThat(retrievedPlan.id()).isEqualTo(plan.id());
        assertThat(retrievedPlan.actions()).hasSize(1);
    }

    @Test
    @DisplayName("Verification of Resource Not Found handling across services")
    void testResourceNotFoundHandling() {
        UUID nonExistentId = UUID.randomUUID();

        assertThatThrownBy(() -> resourceService.getResourceById(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> telemetryService.getTelemetryByResourceId(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> incidentService.getIncidentById(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> recoveryService.getRecoveryPlanByIncidentId(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Phase 1D: Anomaly correlation persists Incident and IncidentAnomalyEvidence with V3 schema")
    void testIncidentAnomalyEvidenceAndCorrelation() {
        // Create resource
        CreateResourceRequest resourceReq = new CreateResourceRequest(
                "correlation-test-host",
                ResourceType.SERVER,
                ResourceStatus.HEALTHY,
                "production",
                "srv-corr-01",
                Map.of("role", "worker")
        );
        ResourceResponse res = resourceService.createResource(resourceReq);

        // Correlate initial anomaly
        com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse anomaly1 =
                new com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse(
                        res.id(),
                        "cpu_usage",
                        94.0,
                        "Z_SCORE",
                        com.aurora.platform.intelligence.anomaly.model.AnomalyStatus.ANOMALOUS,
                        0.95,
                        4.5,
                        3.0,
                        20,
                        45.0,
                        2.5,
                        40.0,
                        50.0,
                        Instant.now()
                );

        IncidentResponse inc1 = incidentCorrelationService.correlateAnomaly(anomaly1);
        assertThat(inc1).isNotNull();
        assertThat(inc1.resourceId()).isEqualTo(res.id());
        assertThat(inc1.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(inc1.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(inc1.rootCause()).isNull();
        assertThat(inc1.confidence()).isNull();

        // Correlate second anomaly within correlation window (same resource)
        com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse anomaly2 =
                new com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse(
                        res.id(),
                        "memory_usage",
                        98.0,
                        "Z_SCORE",
                        com.aurora.platform.intelligence.anomaly.model.AnomalyStatus.ANOMALOUS,
                        0.85,
                        3.8,
                        3.0,
                        20,
                        50.0,
                        3.0,
                        45.0,
                        55.0,
                        Instant.now()
                );

        IncidentResponse inc2 = incidentCorrelationService.correlateAnomaly(anomaly2);
        assertThat(inc2).isNotNull();
        assertThat(inc2.id()).isEqualTo(inc1.id()); // Attached to same incident!

        // Retrieve evidence list through IncidentService
        List<com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse> evidenceList =
                incidentService.getEvidenceByIncidentId(inc1.id());
        assertThat(evidenceList).hasSize(2);
        assertThat(evidenceList.get(0).metricName()).isEqualTo("cpu_usage");
        assertThat(evidenceList.get(1).metricName()).isEqualTo("memory_usage");
    }

    @Test
    @DisplayName("Phase 2A: Resource dependency lifecycle, directionality, and cascade deletion")
    void testResourceDependencyLifecycleAndDirectionality() {
        // Create frontend and api resources
        ResourceResponse frontend = resourceService.createResource(new CreateResourceRequest(
                "dep-test-frontend", ResourceType.APPLICATION, ResourceStatus.HEALTHY,
                "production", "srv-fe-01", Map.of("tier", "web")
        ));
        ResourceResponse api = resourceService.createResource(new CreateResourceRequest(
                "dep-test-api", ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "srv-api-01", Map.of("tier", "app")
        ));

        // Create dependency: frontend DEPENDS_ON api
        com.aurora.platform.dependency.dto.CreateResourceDependencyRequest createReq =
                new com.aurora.platform.dependency.dto.CreateResourceDependencyRequest(
                        api.id(), com.aurora.platform.dependency.entity.DependencyType.DEPENDS_ON
                );

        com.aurora.platform.dependency.dto.ResourceDependencyResponse dep =
                dependencyService.createDependency(frontend.id(), createReq);

        assertThat(dep).isNotNull();
        assertThat(dep.sourceResourceId()).isEqualTo(frontend.id());
        assertThat(dep.targetResourceId()).isEqualTo(api.id());

        // Directionality: frontend dependencies should contain api
        List<com.aurora.platform.dependency.dto.ResourceDependencyResponse> frontendDeps =
                dependencyService.getDependencies(frontend.id());
        assertThat(frontendDeps).hasSize(1);
        assertThat(frontendDeps.get(0).targetResourceId()).isEqualTo(api.id());

        // Directionality: api dependents should contain frontend
        List<com.aurora.platform.dependency.dto.ResourceDependencyResponse> apiDependents =
                dependencyService.getDependents(api.id());
        assertThat(apiDependents).hasSize(1);
        assertThat(apiDependents.get(0).sourceResourceId()).isEqualTo(frontend.id());

        // api has no outgoing dependencies
        assertThat(dependencyService.getDependencies(api.id())).isEmpty();

        // frontend has no incoming dependents
        assertThat(dependencyService.getDependents(frontend.id())).isEmpty();

        // Delete dependency
        dependencyService.deleteDependency(frontend.id(), dep.id());
        assertThat(dependencyService.getDependencies(frontend.id())).isEmpty();
    }

    @Test
    @DisplayName("Phase 2B: Full RCA Evidence Engine lifecycle, deterministic scoring and 1-hop topological evaluation")
    void testRcaEvidenceEngineLifecycleAndScoring() {
        // 1. Create resources: frontend, api, postgres
        ResourceResponse frontend = resourceService.createResource(new CreateResourceRequest(
                "rca-integ-frontend", ResourceType.APPLICATION, ResourceStatus.HEALTHY,
                "production", "srv-rca-fe", Map.of("tier", "web")
        ));
        ResourceResponse api = resourceService.createResource(new CreateResourceRequest(
                "rca-integ-api", ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "srv-rca-api", Map.of("tier", "app")
        ));
        ResourceResponse postgres = resourceService.createResource(new CreateResourceRequest(
                "rca-integ-postgres", ResourceType.DATABASE, ResourceStatus.HEALTHY,
                "production", "srv-rca-db", Map.of("tier", "db")
        ));
        ResourceResponse redis = resourceService.createResource(new CreateResourceRequest(
                "rca-integ-redis", ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "srv-rca-redis", Map.of("tier", "cache")
        ));

        // 2. Topology: frontend -> api -> postgres; api -> redis
        dependencyService.createDependency(frontend.id(), new com.aurora.platform.dependency.dto.CreateResourceDependencyRequest(
                api.id(), com.aurora.platform.dependency.entity.DependencyType.DEPENDS_ON));
        dependencyService.createDependency(api.id(), new com.aurora.platform.dependency.dto.CreateResourceDependencyRequest(
                postgres.id(), com.aurora.platform.dependency.entity.DependencyType.DEPENDS_ON));
        dependencyService.createDependency(api.id(), new com.aurora.platform.dependency.dto.CreateResourceDependencyRequest(
                redis.id(), com.aurora.platform.dependency.entity.DependencyType.DEPENDS_ON));

        Instant baseTime = Instant.now().minusSeconds(120);

        // 3. PostgreSQL anomaly occurred first (preceded API by 60s)
        com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse postgresAnomaly =
                new com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse(
                        postgres.id(), "db_connections", 97.0, "Z_SCORE",
                        com.aurora.platform.intelligence.anomaly.model.AnomalyStatus.ANOMALOUS,
                        0.86, 3.5, 3.0, 20, 50.0, 2.0, 45.0, 55.0, baseTime
                );
        incidentCorrelationService.correlateAnomaly(postgresAnomaly);

        // 4. API incident occurred 60 seconds later
        com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse apiAnomaly =
                new com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse(
                        api.id(), "api_latency", 850.0, "Z_SCORE",
                        com.aurora.platform.intelligence.anomaly.model.AnomalyStatus.ANOMALOUS,
                        0.92, 4.2, 3.0, 20, 200.0, 10.0, 180.0, 220.0, baseTime.plusSeconds(60)
                );
        IncidentResponse apiIncident = incidentCorrelationService.correlateAnomaly(apiAnomaly);
        assertThat(apiIncident).isNotNull();

        // 5. Trigger RCA on API incident multiple times to verify absolute determinism across runs
        com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse rcaRun1 = rcaAnalysisService.analyzeIncident(apiIncident.id());
        com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse rcaRun2 = rcaAnalysisService.analyzeIncident(apiIncident.id());

        assertThat(rcaRun1).isNotNull();
        assertThat(rcaRun2).isNotNull();
        assertThat(rcaRun1.status()).isEqualTo(com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus.COMPLETED);
        assertThat(rcaRun2.status()).isEqualTo(com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus.COMPLETED);
        assertThat(rcaRun1.confidence()).isEqualTo(0.85);
        assertThat(rcaRun2.confidence()).isEqualTo(0.85);
        assertThat(rcaRun1.confidenceLevel()).isEqualTo("VERY_HIGH");
        assertThat(rcaRun2.confidenceLevel()).isEqualTo("VERY_HIGH");

        // Primary candidate must be PostgreSQL in both runs
        assertThat(rcaRun1.candidates()).hasSize(3); // PostgreSQL, API, Redis
        assertThat(rcaRun2.candidates()).hasSize(3);

        for (int i = 0; i < 3; i++) {
            com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse c1 = rcaRun1.candidates().get(i);
            com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse c2 = rcaRun2.candidates().get(i);
            assertThat(c1.candidateResourceId()).isEqualTo(c2.candidateResourceId());
            assertThat(c1.rank()).isEqualTo(c2.rank());
            assertThat(c1.evidenceScore()).isEqualTo(c2.evidenceScore());
            assertThat(c1.primaryCandidate()).isEqualTo(c2.primaryCandidate());
            assertThat(c1.explanation()).isEqualTo(c2.explanation());
            assertThat(c1.evidence()).hasSameSizeAs(c2.evidence());
        }

        com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse rank1 = rcaRun1.candidates().get(0);
        assertThat(rank1.rank()).isEqualTo(1);
        assertThat(rank1.candidateResourceId()).isEqualTo(postgres.id());
        assertThat(rank1.primaryCandidate()).isTrue();
        assertThat(rank1.evidenceScore()).isEqualTo(0.85);
        assertThat(rank1.explanation()).contains("direct dependency of rca-integ-api")
                .contains("db_connections");

        // Rank 2: API itself (ANOMALY only)
        com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse rank2 = rcaRun1.candidates().get(1);
        assertThat(rank2.rank()).isEqualTo(2);
        assertThat(rank2.candidateResourceId()).isEqualTo(api.id());
        assertThat(rank2.evidenceScore()).isEqualTo(0.40);
        assertThat(rank2.primaryCandidate()).isFalse();

        // Rank 3: Redis (DEPENDENCY only)
        com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse rank3 = rcaRun1.candidates().get(2);
        assertThat(rank3.rank()).isEqualTo(3);
        assertThat(rank3.candidateResourceId()).isEqualTo(redis.id());
        assertThat(rank3.evidenceScore()).isEqualTo(0.20);
        assertThat(rank3.primaryCandidate()).isFalse();

        // Verify retrieval of latest RCA
        com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse latestRca =
                rcaAnalysisService.getLatestAnalysisByIncidentId(apiIncident.id());
        assertThat(latestRca.id()).isEqualTo(rcaRun2.id());
    }
}
