package com.aurora.platform.intelligence.anomaly.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.intelligence.anomaly.config.AnomalyDetectionProperties;
import com.aurora.platform.intelligence.anomaly.detector.AnomalyDetector;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyDetectorType;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.incident.service.IncidentCorrelationService;
import com.aurora.platform.resource.service.ResourceService;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnomalyDetectionServiceImpl implements AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionServiceImpl.class);

    private final ResourceService resourceService;
    private final TelemetryEventRepository telemetryEventRepository;
    private final Map<AnomalyDetectorType, AnomalyDetector> detectors;
    private final AnomalyDetectionProperties properties;
    private final IncidentCorrelationService incidentCorrelationService;

    @Autowired
    public AnomalyDetectionServiceImpl(
            ResourceService resourceService,
            TelemetryEventRepository telemetryEventRepository,
            List<AnomalyDetector> anomalyDetectors,
            AnomalyDetectionProperties properties,
            IncidentCorrelationService incidentCorrelationService) {
        this.resourceService = resourceService;
        this.telemetryEventRepository = telemetryEventRepository;
        this.properties = properties;
        this.incidentCorrelationService = incidentCorrelationService;
        this.detectors = new EnumMap<>(AnomalyDetectorType.class);
        if (anomalyDetectors != null) {
            for (AnomalyDetector detector : anomalyDetectors) {
                try {
                    this.detectors.put(detector.getType(), detector);
                } catch (Exception e) {
                    log.warn("Could not register anomaly detector '{}': {}", detector.getMethodName(), e.getMessage());
                }
            }
        }
    }

    public AnomalyDetectionServiceImpl(
            ResourceService resourceService,
            TelemetryEventRepository telemetryEventRepository,
            AnomalyDetector anomalyDetector,
            AnomalyDetectionProperties properties) {
        this(resourceService, telemetryEventRepository, List.of(anomalyDetector), properties, null);
    }

    public AnomalyDetectionServiceImpl(
            ResourceService resourceService,
            TelemetryEventRepository telemetryEventRepository,
            AnomalyDetector anomalyDetector,
            AnomalyDetectionProperties properties,
            IncidentCorrelationService incidentCorrelationService) {
        this(resourceService, telemetryEventRepository, List.of(anomalyDetector), properties, incidentCorrelationService);
    }

    /**
     * Resolves the configured AnomalyDetector instance based on application properties.
     */
    public AnomalyDetector resolveDetector() {
        AnomalyDetectorType type = properties.getDetectorType();
        AnomalyDetector detector = detectors.get(type);
        if (detector == null) {
            if (detectors.size() == 1) {
                return detectors.values().iterator().next();
            }
            throw new IllegalStateException("No AnomalyDetector bean registered for type: " + type);
        }
        return detector;
    }

    @Override
    @Transactional
    public AnomalyDetectionResponse evaluateAnomaly(UUID resourceId, String metricName, Double value, Double thresholdOverride) {
        log.debug("Evaluating anomaly for resource ID {}, metric '{}', value={}", resourceId, metricName, value);

        if (resourceId == null) {
            throw new ValidationException("Resource ID is required");
        }

        if (metricName == null || metricName.isBlank()) {
            throw new ValidationException("Metric name is required");
        }

        if (thresholdOverride != null && (Double.isNaN(thresholdOverride) || Double.isInfinite(thresholdOverride))) {
            throw new ValidationException("Anomaly threshold must be a finite number");
        }
        double threshold = (thresholdOverride != null) ? thresholdOverride : properties.getDefaultThreshold();
        if (threshold <= 0.0) {
            throw new ValidationException("Anomaly threshold must be greater than zero");
        }

        if (!resourceService.existsById(resourceId)) {
            throw new ResourceNotFoundException("Resource with ID '" + resourceId + "' not found");
        }

        List<TelemetryEventEntity> events = telemetryEventRepository
                .findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, metricName.trim());

        double currentValue;
        Instant evaluatedAt;
        List<Double> baselineSamples;

        if (value != null) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new ValidationException("Anomaly evaluation value must be a finite number");
            }
            currentValue = value;
            evaluatedAt = Instant.now();
            baselineSamples = events.stream().map(TelemetryEventEntity::getValue).toList();
        } else {
            if (events.isEmpty()) {
                throw new ResourceNotFoundException(
                        "No telemetry events found for metric '" + metricName.trim() + "' on resource '" + resourceId + "'");
            }
            TelemetryEventEntity latestEvent = events.get(events.size() - 1);
            currentValue = latestEvent.getValue();
            evaluatedAt = latestEvent.getTimestamp();
            baselineSamples = events.subList(0, events.size() - 1).stream().map(TelemetryEventEntity::getValue).toList();
        }

        AnomalyDetector detector = resolveDetector();
        log.debug("Using anomaly detector '{}' ({}) for resource ID {}, metric '{}'",
                detector.getMethodName(), detector.getType(), resourceId, metricName);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId,
                metricName.trim(),
                currentValue,
                baselineSamples,
                threshold,
                properties.getMinSampleCount(),
                evaluatedAt
        );

        if (response.status() == AnomalyStatus.ANOMALOUS && incidentCorrelationService != null) {
            incidentCorrelationService.correlateAnomaly(response);
        }

        return response;
    }
}
