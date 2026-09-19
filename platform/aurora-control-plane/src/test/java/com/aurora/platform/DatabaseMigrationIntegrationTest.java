package com.aurora.platform;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incidents.dto.CreateIncidentRequest;
import com.aurora.platform.incidents.dto.IncidentResponse;
import com.aurora.platform.incidents.entity.IncidentSeverity;
import com.aurora.platform.incidents.entity.IncidentStatus;
import com.aurora.platform.incidents.service.IncidentService;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.service.RecoveryService;
import com.aurora.platform.resources.dto.CreateResourceRequest;
import com.aurora.platform.resources.dto.ResourceResponse;
import com.aurora.platform.resources.entity.ResourceStatus;
import com.aurora.platform.resources.entity.ResourceType;
import com.aurora.platform.resources.service.ResourceService;
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
}
