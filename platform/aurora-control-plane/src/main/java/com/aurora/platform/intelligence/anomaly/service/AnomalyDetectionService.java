package com.aurora.platform.intelligence.anomaly.service;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;

import java.util.UUID;

/**
 * Service for orchestrating anomaly detection across historical telemetry series.
 */
public interface AnomalyDetectionService {

    /**
     * Evaluates whether an observation for a given resource and metric is anomalous.
     *
     * @param resourceId ID of the resource
     * @param metricName Name of the metric to evaluate
     * @param value      Explicit observation value to evaluate, or null to evaluate the latest recorded telemetry event
     * @param threshold  Optional anomaly sensitivity threshold override, or null to use default configured threshold
     * @return Evaluation response
     */
    AnomalyDetectionResponse evaluateAnomaly(UUID resourceId, String metricName, Double value, Double threshold);
}
