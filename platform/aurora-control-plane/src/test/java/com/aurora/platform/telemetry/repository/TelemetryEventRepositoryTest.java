package com.aurora.platform.telemetry.repository;

import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.entity.TelemetryType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TelemetryEventRepositoryTest {

    @Autowired
    private TelemetryEventRepository telemetryEventRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ResourceEntity createAndPersistResource(String name) {
        ResourceEntity resource = ResourceEntity.builder()
                .name(name)
                .type(ResourceType.SERVER)
                .status(ResourceStatus.HEALTHY)
                .environment("development")
                .host("host-" + name)
                .build();
        return entityManager.persistAndFlush(resource);
    }

    @Test
    @DisplayName("Should persist and retrieve telemetry event for a valid resource")
    void shouldPersistAndRetrieveTelemetryEvent() {
        ResourceEntity resource = createAndPersistResource("server-telemetry-01");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        TelemetryEventEntity event = TelemetryEventEntity.builder()
                .resourceId(resource.getId())
                .timestamp(now)
                .type(TelemetryType.METRIC)
                .metricName("cpu_usage")
                .value(78.4)
                .unit("percent")
                .metadata("{\"core\":0}")
                .build();

        TelemetryEventEntity saved = telemetryEventRepository.save(event);
        entityManager.flush();
        entityManager.clear();

        Optional<TelemetryEventEntity> retrieved = telemetryEventRepository.findById(saved.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getResourceId()).isEqualTo(resource.getId());
        assertThat(retrieved.get().getMetricName()).isEqualTo("cpu_usage");
        assertThat(retrieved.get().getValue()).isEqualTo(78.4);
        assertThat(retrieved.get().getUnit()).isEqualTo("percent");
        assertThat(retrieved.get().getType()).isEqualTo(TelemetryType.METRIC);
        assertThat(retrieved.get().getMetadata()).isEqualTo("{\"core\":0}");
    }

    @Test
    @DisplayName("Should retrieve telemetry ordered oldest to newest (timestamp ASC)")
    void shouldRetrieveTelemetryChronologicallyAsc() {
        ResourceEntity resource = createAndPersistResource("server-telemetry-02");
        Instant t1 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant t3 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Save out of order to ensure repository sorts properly
        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(t2).type(TelemetryType.METRIC)
                .metricName("cpu_usage").value(60.0).unit("percent").build());
        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(t3).type(TelemetryType.METRIC)
                .metricName("cpu_usage").value(80.0).unit("percent").build());
        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(t1).type(TelemetryType.METRIC)
                .metricName("cpu_usage").value(40.0).unit("percent").build());

        entityManager.flush();
        entityManager.clear();

        List<TelemetryEventEntity> results = telemetryEventRepository.findByResourceIdOrderByTimestampAsc(resource.getId());

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getTimestamp()).isEqualTo(t1);
        assertThat(results.get(0).getValue()).isEqualTo(40.0);
        assertThat(results.get(1).getTimestamp()).isEqualTo(t2);
        assertThat(results.get(1).getValue()).isEqualTo(60.0);
        assertThat(results.get(2).getTimestamp()).isEqualTo(t3);
        assertThat(results.get(2).getValue()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("Should filter telemetry by resource ID and metric name ordered oldest to newest")
    void shouldFilterByMetricNameAndOrderChronologicallyAsc() {
        ResourceEntity resource = createAndPersistResource("server-telemetry-03");
        Instant t1 = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);
        Instant t2 = Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MILLIS);

        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(t2).type(TelemetryType.METRIC)
                .metricName("memory_usage").value(65.0).unit("percent").build());
        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(t1).type(TelemetryType.METRIC)
                .metricName("memory_usage").value(55.0).unit("percent").build());
        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(Instant.now()).type(TelemetryType.METRIC)
                .metricName("cpu_usage").value(90.0).unit("percent").build());

        entityManager.flush();
        entityManager.clear();

        List<TelemetryEventEntity> memoryEvents = telemetryEventRepository
                .findByResourceIdAndMetricNameOrderByTimestampAsc(resource.getId(), "memory_usage");

        assertThat(memoryEvents).hasSize(2);
        assertThat(memoryEvents.get(0).getTimestamp()).isEqualTo(t1);
        assertThat(memoryEvents.get(0).getValue()).isEqualTo(55.0);
        assertThat(memoryEvents.get(1).getTimestamp()).isEqualTo(t2);
        assertThat(memoryEvents.get(1).getValue()).isEqualTo(65.0);
    }

    @Test
    @DisplayName("Should cascade delete telemetry events when resource is removed")
    void shouldCascadeDeleteTelemetryWhenResourceDeleted() {
        ResourceEntity resource = createAndPersistResource("server-telemetry-04");

        telemetryEventRepository.save(TelemetryEventEntity.builder()
                .resourceId(resource.getId()).timestamp(Instant.now()).type(TelemetryType.METRIC)
                .metricName("db_connections").value(10.0).unit("connections").build());

        entityManager.flush();
        entityManager.clear();

        ResourceEntity attachedResource = entityManager.find(ResourceEntity.class, resource.getId());
        entityManager.remove(attachedResource);
        entityManager.flush();
        entityManager.clear();

        List<TelemetryEventEntity> events = telemetryEventRepository.findByResourceIdOrderByTimestampAsc(resource.getId());
        assertThat(events).isEmpty();
    }
}
