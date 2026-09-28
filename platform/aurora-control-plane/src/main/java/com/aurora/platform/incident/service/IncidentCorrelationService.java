package com.aurora.platform.incident.service;

import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;

/**
 * Service for deterministic correlation of statistical anomalies into persistent incidents.
 */
public interface IncidentCorrelationService {

    /**
     * Correlates an anomalous telemetry evaluation against active incidents on the resource.
     * If an active incident exists within the correlation window, the anomaly is attached as evidence.
     * Otherwise, a new incident is created in DETECTED status and the anomaly evidence is attached.
     *
     * @param anomaly Anomaly evaluation result
     * @return IncidentResponse for the correlated or newly created incident, or null if not anomalous
     */
    IncidentResponse correlateAnomaly(AnomalyDetectionResponse anomaly);
}
