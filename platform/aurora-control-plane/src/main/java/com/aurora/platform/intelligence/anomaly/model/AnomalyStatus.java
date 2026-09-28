package com.aurora.platform.intelligence.anomaly.model;

/**
 * Represents the statistical classification of a telemetry observation.
 */
public enum AnomalyStatus {
    /**
     * Observation falls within normal statistical baseline expectation (|z| < threshold).
     */
    NORMAL,

    /**
     * Observation deviates significantly from historical baseline (|z| >= threshold or deviates from zero-variance baseline).
     */
    ANOMALOUS,

    /**
     * Baseline contains fewer historical observations than the required minimum sample threshold.
     */
    INSUFFICIENT_DATA
}
