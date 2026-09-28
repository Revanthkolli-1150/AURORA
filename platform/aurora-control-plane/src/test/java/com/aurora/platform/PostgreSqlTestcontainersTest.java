package com.aurora.platform;

import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.service.ResourceDependencyService;
import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.service.TelemetryService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlTestcontainersTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aurora_test")
            .withUsername("aurora_user")
            .withPassword("aurora_pass");

    @BeforeAll
    static void checkDockerAvailability() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker daemon is not running; skipping PostgreSqlTestcontainersTest"
        );
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (DockerClientFactory.instance().isDockerAvailable() && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.flyway.enabled", () -> "true");
        }
    }

    @Autowired(required = false)
    private ResourceService resourceService;

    @Autowired(required = false)
    private TelemetryService telemetryService;

    @Autowired(required = false)
    private IncidentService incidentService;

    @Autowired(required = false)
    private ResourceDependencyService dependencyService;

    @Autowired(required = false)
    private RcaAnalysisService rcaAnalysisService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    private void ensureDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable() && postgres.isRunning(),
                "Docker daemon is not running; skipping PostgreSQL container execution"
        );
    }

    @Test
    @DisplayName("PG-1: Running database engine must be verified as PostgreSQL 16.x")
    void testPostgreSqlVersion() {
        ensureDocker();
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertThat(version).isNotNull();
        assertThat(version).containsIgnoringCase("PostgreSQL 16");
    }

    @Test
    @DisplayName("PG-2: Flyway migrations V1 through V5 apply cleanly on empty database")
    void testFlywayMigrationsApplied() {
        ensureDocker();
        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class
        );
        assertThat(successfulMigrations).isNotNull();
        assertThat(successfulMigrations).isGreaterThanOrEqualTo(5);

        List<String> scriptNames = jdbcTemplate.queryForList(
                "SELECT script FROM flyway_schema_history WHERE success = true ORDER BY installed_rank ASC",
                String.class
        );
        assertThat(scriptNames).contains(
                "V1__init_control_plane_schema.sql",
                "V2__telemetry_indexes.sql",
                "V3__incident_anomaly_evidence.sql",
                "V4__resource_dependencies.sql",
                "V5__rca_evidence_engine.sql"
        );
    }

    @Test
    @DisplayName("PG-3: Resource persistence and metadata roundtrip")
    void testResourcePersistence() {
        ensureDocker();
        CreateResourceRequest request = new CreateResourceRequest(
                "postgres-test-srv-" + UUID.randomUUID().toString().substring(0, 8),
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-pg-01",
                Map.of("cluster", "primary", "az", "us-east-1a")
        );

        ResourceResponse created = resourceService.createResource(request);
        assertThat(created.id()).isNotNull();

        ResourceResponse fetched = resourceService.getResourceById(created.id());
        assertThat(fetched.name()).isEqualTo(request.name());
        assertThat(fetched.metadata()).containsEntry("cluster", "primary");
    }

    @Test
    @DisplayName("PG-4: Telemetry persistence with resource foreign key constraint")
    void testTelemetryPersistenceAndFk() {
        ensureDocker();
        ResourceResponse res = resourceService.createResource(new CreateResourceRequest(
                "postgres-telemetry-host-" + UUID.randomUUID().toString().substring(0, 8),
                ResourceType.SERVER,
                ResourceStatus.HEALTHY,
                "production",
                "srv-telem-01",
                Map.of()
        ));

        TelemetryEventResponse event = telemetryService.ingestTelemetry(new IngestTelemetryRequest(
                res.id(),
                Instant.now(),
                TelemetryType.METRIC,
                "cpu.usage",
                88.5,
                "percent",
                Map.of("core", "0")
        ));
        assertThat(event.id()).isNotNull();
        assertThat(event.resourceId()).isEqualTo(res.id());

        // Foreign key violation when inserting telemetry for non-existent resource
        assertThatThrownBy(() -> telemetryService.ingestTelemetry(new IngestTelemetryRequest(
                UUID.randomUUID(),
                Instant.now(),
                TelemetryType.METRIC,
                "cpu.usage",
                50.0,
                "percent",
                Map.of()
        ))).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("PG-5: Incident persistence and status transition lifecycle")
    void testIncidentPersistenceAndLifecycle() {
        ensureDocker();
        ResourceResponse res = resourceService.createResource(new CreateResourceRequest(
                "postgres-incident-host-" + UUID.randomUUID().toString().substring(0, 8),
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-inc-01",
                Map.of()
        ));

        IncidentResponse inc = incidentService.createIncident(new CreateIncidentRequest(
                res.id(),
                "PG Test Outage",
                "Connection timeout",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                0.90,
                null,
                Instant.now()
        ));
        assertThat(inc.id()).isNotNull();
        assertThat(inc.status()).isEqualTo(IncidentStatus.DETECTED);

        IncidentResponse updated = incidentService.updateIncidentStatus(inc.id(), IncidentStatus.INVESTIGATING);
        assertThat(updated.status()).isEqualTo(IncidentStatus.INVESTIGATING);
    }

    @Test
    @DisplayName("PG-6: Resource dependency constraints: self-loop rejection and directional uniqueness")
    void testResourceDependencyConstraints() {
        ensureDocker();
        ResourceResponse r1 = resourceService.createResource(new CreateResourceRequest(
                "dep-node-1-" + UUID.randomUUID().toString().substring(0, 8),
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-dep-1",
                Map.of()
        ));
        ResourceResponse r2 = resourceService.createResource(new CreateResourceRequest(
                "dep-node-2-" + UUID.randomUUID().toString().substring(0, 8),
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-dep-2",
                Map.of()
        ));

        // Self-loop must violate chk_dependency_not_self
        assertThatThrownBy(() -> dependencyService.createDependency(r1.id(), new CreateResourceDependencyRequest(
                r1.id(), DependencyType.DEPENDS_ON
        ))).isInstanceOf(Exception.class);

        // First dependency succeeds
        ResourceDependencyResponse dep = dependencyService.createDependency(r1.id(), new CreateResourceDependencyRequest(
                r2.id(), DependencyType.DEPENDS_ON
        ));
        assertThat(dep.id()).isNotNull();

        // Duplicate dependency must violate uq_resource_dependency
        assertThatThrownBy(() -> dependencyService.createDependency(r1.id(), new CreateResourceDependencyRequest(
                r2.id(), DependencyType.DEPENDS_ON
        ))).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("PG-7: RCA evidence engine persistence and deterministic candidate ranking")
    void testRcaPersistenceAndRanking() {
        ensureDocker();
        ResourceResponse res = resourceService.createResource(new CreateResourceRequest(
                "rca-pg-node-" + UUID.randomUUID().toString().substring(0, 8),
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-rca-01",
                Map.of()
        ));

        IncidentResponse inc = incidentService.createIncident(new CreateIncidentRequest(
                res.id(),
                "RCA Incident",
                "High Error Rate",
                IncidentSeverity.CRITICAL,
                IncidentStatus.DETECTED,
                0.95,
                null,
                Instant.now()
        ));

        RcaAnalysisResponse rca = rcaAnalysisService.analyzeIncident(inc.id());
        assertThat(rca).isNotNull();
        assertThat(rca.candidates()).isNotEmpty();

        RcaCandidateResponse primary = rca.candidates().get(0);
        assertThat(primary.rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("PG-8: PostgreSQL transaction boundary rollback on error")
    void testTransactionRollback() {
        ensureDocker();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        String testName = "rollback-test-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            tx.execute(status -> {
                resourceService.createResource(new CreateResourceRequest(
                        testName,
                        ResourceType.SERVICE,
                        ResourceStatus.HEALTHY,
                        "test",
                        "host-rb",
                        Map.of()
                ));
                throw new RuntimeException("Simulated transaction failure");
            });
        } catch (RuntimeException ignored) {
            // Expected
        }

        // Verify that resource creation was rolled back
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resources WHERE name = ?",
                Integer.class,
                testName
        );
        assertThat(count).isZero();
    }
}
