package com.aurora.platform.intelligence.anomaly.dto;

import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * API response representing the statistical anomaly evaluation of a telemetry observation.
 */
public record AnomalyDetectionResponse(
        UUID resourceId,
        String metricName,
        Double currentValue,
        String detectionMethod,
        AnomalyStatus status,
        Double anomalyScore,
        Double zScore,
        Double threshold,
        Integer sampleCount,
        Double mean,
        Double standardDeviation,
        Double minimum,
        Double maximum,
        Instant evaluatedAt
) {}
