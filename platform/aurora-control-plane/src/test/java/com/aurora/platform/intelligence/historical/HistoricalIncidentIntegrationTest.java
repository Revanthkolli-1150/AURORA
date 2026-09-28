package com.aurora.platform.intelligence.historical;

import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.service.ResourceDependencyService;
import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentCorrelationService;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.intelligence.historical.service.HistoricalIncidentService;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HistoricalIncidentIntegrationTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceDependencyService dependencyService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentCorrelationService correlationService;

    @Autowired
    private RcaAnalysisService rcaAnalysisService;

    @Autowired
    private HistoricalIncidentService historicalService;

    @Test
    @DisplayName("End-to-End Phase 2C: Deterministically find historically similar resolved incidents with RCA context")
    void testEndToEndHistoricalIncidentSimilarity() {
        // 1. Create resources: billing-db and billing-api
        ResourceResponse db = resourceService.createResource(new CreateResourceRequest(
                "hist-test-db-" + UUID.randomUUID().toString().substring(0, 6),
                ResourceType.DATABASE, ResourceStatus.HEALTHY,
                "production", "srv-db", Map.of()
        ));

        ResourceResponse api = resourceService.createResource(new CreateResourceRequest(
                "hist-test-api-" + UUID.randomUUID().toString().substring(0, 6),
                ResourceType.SERVICE, ResourceStatus.HEALTHY,
                "production", "srv-api", Map.of()
        ));

        // Topology: api DEPENDS_ON db (db has DOWNSTREAM:SERVICE)
        dependencyService.createDependency(api.id(), new CreateResourceDependencyRequest(
                db.id(), DependencyType.DEPENDS_ON
        ));

        Instant baseTime = Instant.now().minusSeconds(3600);

        // 2. Create Historical Incident 1 (RESOLVED, CRITICAL, db_connections, with RCA)
        AnomalyDetectionResponse dbAnomaly1 = new AnomalyDetectionResponse(
                db.id(), "db_connections", 98.0, "Z_SCORE",
                AnomalyStatus.ANOMALOUS, 0.95, 4.5, 3.0, 20, 50.0, 2.0, 45.0, 55.0, baseTime
        );
        IncidentResponse histInc1 = correlationService.correlateAnomaly(dbAnomaly1);
        // Run RCA on histInc1
        RcaAnalysisResponse rca1 = rcaAnalysisService.analyzeIncident(histInc1.id());
        assertThat(rca1).isNotNull();

        // Progress histInc1 to RESOLVED: DETECTED -> INVESTIGATING -> DIAGNOSED -> RECOVERING -> VERIFYING -> RESOLVED
        incidentService.updateIncidentStatus(histInc1.id(), IncidentStatus.INVESTIGATING);
        incidentService.updateIncidentStatus(histInc1.id(), IncidentStatus.DIAGNOSED);
        incidentService.updateIncidentStatus(histInc1.id(), IncidentStatus.RECOVERING);
        incidentService.updateIncidentStatus(histInc1.id(), IncidentStatus.VERIFYING);
        incidentService.updateIncidentStatus(histInc1.id(), IncidentStatus.RESOLVED);

        // 3. Create Historical Incident 2 (RESOLVED, HIGH severity, db_connections, resolved earlier)
        AnomalyDetectionResponse dbAnomaly2 = new AnomalyDetectionResponse(
                db.id(), "db_connections", 85.0, "Z_SCORE",
                AnomalyStatus.ANOMALOUS, 0.85, 3.5, 3.0, 20, 50.0, 2.0, 45.0, 55.0, baseTime.minusSeconds(1800)
        );
        IncidentResponse histInc2 = correlationService.correlateAnomaly(dbAnomaly2);
        incidentService.updateIncidentStatus(histInc2.id(), IncidentStatus.INVESTIGATING);
        incidentService.updateIncidentStatus(histInc2.id(), IncidentStatus.DIAGNOSED);
        incidentService.updateIncidentStatus(histInc2.id(), IncidentStatus.RECOVERING);
        incidentService.updateIncidentStatus(histInc2.id(), IncidentStatus.VERIFYING);
        incidentService.updateIncidentStatus(histInc2.id(), IncidentStatus.RESOLVED);

        // 4. Create Historical Incident 3 (FAILED - MUST BE EXCLUDED)
        AnomalyDetectionResponse dbAnomaly3 = new AnomalyDetectionResponse(
                db.id(), "db_connections", 99.0, "Z_SCORE",
                AnomalyStatus.ANOMALOUS, 0.99, 5.0, 3.0, 20, 50.0, 2.0, 45.0, 55.0, baseTime.minusSeconds(3600)
        );
        IncidentResponse histInc3Failed = correlationService.correlateAnomaly(dbAnomaly3);
        incidentService.updateIncidentStatus(histInc3Failed.id(), IncidentStatus.FAILED);

        // 5. Create Active Incident 4 (DETECTED - MUST BE EXCLUDED from candidates)
        AnomalyDetectionResponse dbAnomaly4 = new AnomalyDetectionResponse(
                db.id(), "cpu_usage", 95.0, "Z_SCORE",
                AnomalyStatus.ANOMALOUS, 0.90, 4.0, 3.0, 20, 40.0, 2.0, 35.0, 45.0, Instant.now().minusSeconds(600)
        );
        IncidentResponse histInc4Active = correlationService.correlateAnomaly(dbAnomaly4);

        // 6. Create Target Incident (Active, CRITICAL, db_connections)
        AnomalyDetectionResponse targetAnomaly = new AnomalyDetectionResponse(
                db.id(), "db_connections", 96.0, "Z_SCORE",
                AnomalyStatus.ANOMALOUS, 0.96, 4.1, 3.0, 20, 50.0, 2.0, 45.0, 55.0, Instant.now()
        );
        IncidentResponse targetInc = correlationService.correlateAnomaly(targetAnomaly);

        // 7. Find Similar Incidents for Target Incident
        List<SimilarIncidentResponse> matches =
                historicalService.findSimilarIncidents(targetInc.id(), 5, 0.30);

        assertThat(matches).isNotEmpty();
        assertThat(matches).hasSize(2); // Exactly histInc1 and histInc2

        // Verify exclusion guarantees:
        List<UUID> matchedIds = matches.stream().map(SimilarIncidentResponse::historicalIncidentId).toList();
        assertThat(matchedIds).contains(histInc1.id(), histInc2.id());
        assertThat(matchedIds).doesNotContain(histInc3Failed.id());   // FAILED excluded
        assertThat(matchedIds).doesNotContain(histInc4Active.id());   // Active excluded
        assertThat(matchedIds).doesNotContain(targetInc.id());         // Target self-match excluded

        // Verify ranking:
        // histInc1: identical severity (CRITICAL vs CRITICAL) -> severityScore = 1.0 -> composite = 1.00
        // histInc2: adjacent severity (CRITICAL vs HIGH) -> severityScore = 0.5 -> composite = 0.95
        SimilarIncidentResponse rank1 = matches.get(0);
        SimilarIncidentResponse rank2 = matches.get(1);

        assertThat(rank1.historicalIncidentId()).isEqualTo(histInc1.id());
        assertThat(rank1.similarityScore()).isEqualTo(1.00);
        assertThat(rank1.resourceType()).isEqualTo(ResourceType.DATABASE);
        assertThat(rank1.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(rank1.primaryRcaCause()).isNotNull();
        assertThat(rank1.explanation()).contains("Matched 100.0%");

        assertThat(rank2.historicalIncidentId()).isEqualTo(histInc2.id());
        assertThat(rank2.similarityScore()).isEqualTo(0.95);
        assertThat(rank2.breakdown().metricScore()).isEqualTo(1.00);
        assertThat(rank2.breakdown().severityScore()).isEqualTo(0.50);

        // 8. Determinism check: evaluate again and assert identical candidate ordering and scores
        List<SimilarIncidentResponse> repeatMatches =
                historicalService.findSimilarIncidents(targetInc.id(), 5, 0.30);
        assertThat(repeatMatches).hasSize(matches.size());
        for (int i = 0; i < matches.size(); i++) {
            assertThat(repeatMatches.get(i).historicalIncidentId()).isEqualTo(matches.get(i).historicalIncidentId());
            assertThat(repeatMatches.get(i).similarityScore()).isEqualTo(matches.get(i).similarityScore());
        }
    }
}
