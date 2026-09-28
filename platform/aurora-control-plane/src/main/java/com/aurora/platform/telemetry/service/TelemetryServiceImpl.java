package com.aurora.platform.telemetry.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.resource.service.ResourceService;
import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TelemetryServiceImpl implements TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryServiceImpl.class);

    private final TelemetryEventRepository telemetryEventRepository;
    private final ResourceService resourceService;
    private final ObjectMapper objectMapper;

    public TelemetryServiceImpl(TelemetryEventRepository telemetryEventRepository,
                                ResourceService resourceService,
                                ObjectMapper objectMapper) {
        this.telemetryEventRepository = telemetryEventRepository;
        this.resourceService = resourceService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public TelemetryEventResponse ingestTelemetry(IngestTelemetryRequest request) {
        log.debug("Ingesting telemetry for resource ID {}: metric='{}', value={}",
                request.resourceId(), request.metricName(), request.value());

        if (request.value() == null || Double.isNaN(request.value()) || Double.isInfinite(request.value())) {
            throw new ValidationException("Telemetry value must be a finite number");
        }

        if (request.type() != TelemetryType.METRIC) {
            throw new ValidationException("Only METRIC telemetry ingestion is supported in Phase 1B");
        }

        if (!resourceService.existsById(request.resourceId())) {
            throw new ResourceNotFoundException("Resource with ID '" + request.resourceId() + "' not found");
        }

        String metadataJson = null;
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.metadata());
            } catch (Exception e) {
                log.warn("Failed to serialize telemetry metadata to JSON", e);
            }
        }

        Instant timestamp = request.timestamp() != null ? request.timestamp() : Instant.now();

        TelemetryEventEntity entity = TelemetryEventEntity.builder()
                .resourceId(request.resourceId())
                .timestamp(timestamp)
                .type(request.type())
                .metricName(request.metricName().trim())
                .value(request.value())
                .unit(request.unit().trim())
                .metadata(metadataJson)
                .build();

        TelemetryEventEntity saved = telemetryEventRepository.save(entity);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TelemetryEventResponse> getTelemetryByResourceId(UUID resourceId, String metricName) {
        if (!resourceService.existsById(resourceId)) {
            throw new ResourceNotFoundException("Resource with ID '" + resourceId + "' not found");
        }

        List<TelemetryEventEntity> entities;
        if (metricName != null && !metricName.isBlank()) {
            entities = telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, metricName.trim());
        } else {
            entities = telemetryEventRepository.findByResourceIdOrderByTimestampAsc(resourceId);
        }

        return entities.stream()
                .map(this::mapToResponse)
                .toList();
    }

    private TelemetryEventResponse mapToResponse(TelemetryEventEntity entity) {
        Map<String, Object> metadata = Collections.emptyMap();
        if (entity.getMetadata() != null && !entity.getMetadata().isBlank()) {
            try {
                metadata = objectMapper.readValue(entity.getMetadata(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize telemetry metadata JSON for ID {}", entity.getId(), e);
            }
        }

        return new TelemetryEventResponse(
                entity.getId(),
                entity.getResourceId(),
                entity.getTimestamp(),
                entity.getType(),
                entity.getMetricName(),
                entity.getValue(),
                entity.getUnit(),
                metadata
        );
    }
}
