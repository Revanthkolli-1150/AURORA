package com.aurora.platform.telemetry.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.resources.service.ResourceService;
import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    @Mock
    private TelemetryEventRepository telemetryEventRepository;

    @Mock
    private ResourceService resourceService;

    private ObjectMapper objectMapper;
    private TelemetryService telemetryService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        telemetryService = new TelemetryServiceImpl(telemetryEventRepository, resourceService, objectMapper);
    }

    @Test
    @DisplayName("Should ingest telemetry event successfully when resource exists")
    void shouldIngestTelemetrySuccessfully() {
        UUID resourceId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        IngestTelemetryRequest request = new IngestTelemetryRequest(
                resourceId,
                now,
                TelemetryType.METRIC,
                "cpu.usage.percent",
                87.5,
                "percent",
                Map.of("core", 4)
        );

        when(resourceService.existsById(resourceId)).thenReturn(true);

        TelemetryEventEntity savedEntity = TelemetryEventEntity.builder()
                .id(eventId)
                .resourceId(resourceId)
                .timestamp(now)
                .type(TelemetryType.METRIC)
                .metricName("cpu.usage.percent")
                .value(87.5)
                .unit("percent")
                .metadata("{\"core\":4}")
                .build();

        when(telemetryEventRepository.save(any(TelemetryEventEntity.class))).thenReturn(savedEntity);

        TelemetryEventResponse response = telemetryService.ingestTelemetry(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(eventId);
        assertThat(response.resourceId()).isEqualTo(resourceId);
        assertThat(response.metricName()).isEqualTo("cpu.usage.percent");
        assertThat(response.value()).isEqualTo(87.5);
        assertThat(response.unit()).isEqualTo("percent");
        assertThat(response.metadata()).containsEntry("core", 4);

        verify(telemetryEventRepository).save(any(TelemetryEventEntity.class));
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when ingesting telemetry for non-existent resource")
    void shouldThrowNotFoundWhenResourceMissingOnIngest() {
        UUID nonExistentId = UUID.randomUUID();
        IngestTelemetryRequest request = new IngestTelemetryRequest(
                nonExistentId,
                Instant.now(),
                TelemetryType.METRIC,
                "memory.free.mb",
                512.0,
                "mb",
                null
        );

        when(resourceService.existsById(nonExistentId)).thenReturn(false);

        assertThatThrownBy(() -> telemetryService.ingestTelemetry(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Resource with ID '" + nonExistentId + "' not found");

        verify(telemetryEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should retrieve telemetry events for a valid resource")
    void shouldRetrieveTelemetryForResource() {
        UUID resourceId = UUID.randomUUID();
        when(resourceService.existsById(resourceId)).thenReturn(true);

        TelemetryEventEntity entity = TelemetryEventEntity.builder()
                .id(UUID.randomUUID())
                .resourceId(resourceId)
                .timestamp(Instant.now())
                .type(TelemetryType.METRIC)
                .metricName("http.requests.latency")
                .value(45.2)
                .unit("ms")
                .build();

        when(telemetryEventRepository.findByResourceIdOrderByTimestampDesc(resourceId))
                .thenReturn(List.of(entity));

        List<TelemetryEventResponse> results = telemetryService.getTelemetryByResourceId(resourceId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).metricName()).isEqualTo("http.requests.latency");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when querying telemetry for missing resource")
    void shouldThrowNotFoundWhenQueryingMissingResource() {
        UUID nonExistentId = UUID.randomUUID();
        when(resourceService.existsById(nonExistentId)).thenReturn(false);

        assertThatThrownBy(() -> telemetryService.getTelemetryByResourceId(nonExistentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Resource with ID '" + nonExistentId + "' not found");
    }
}
