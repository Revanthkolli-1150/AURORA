package com.aurora.platform.intelligence.rca.entity;

/**
 * Types of deterministic evidence supporting candidate root causes in RCA.
 */
public enum RcaEvidenceType {
    /**
     * Candidate resource exhibited an anomalous observation within the investigation window.
     */
    ANOMALY,

    /**
     * Candidate anomaly occurred before the investigated incident anomaly.
     */
    TEMPORAL_PRECEDENCE,

    /**
     * The investigated incident resource directly depends on the candidate resource (DEPENDS_ON).
     */
    DEPENDENCY,

    /**
     * Telemetry for the candidate moved abnormally or correlated during the incident window.
     */
    TELEMETRY_CORRELATION
}
