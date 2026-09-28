package com.aurora.platform.intelligence.anomaly.model;

/**
 * Enumeration of supported statistical anomaly detector algorithms in AURORA.
 */
public enum AnomalyDetectorType {
    /**
     * Standard Z-Score anomaly detector utilizing sample mean and standard deviation.
     */
    Z_SCORE,

    /**
     * Robust anomaly detector utilizing Median Absolute Deviation (MAD) and robust scale.
     */
    MAD
}
