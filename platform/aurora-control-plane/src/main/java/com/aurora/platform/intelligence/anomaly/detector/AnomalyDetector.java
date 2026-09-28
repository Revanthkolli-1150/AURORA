package com.aurora.platform.intelligence.anomaly.detector;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyDetectorType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Common abstraction for anomaly detection algorithms in AURORA.
 */
public interface AnomalyDetector {

    /**
     * Unique identifier for the detection algorithm (e.g., "Z_SCORE", "MAD").
     */
    String getMethodName();

    /**
     * Returns the strongly-typed enum representation of the detector algorithm.
     */
    default AnomalyDetectorType getType() {
        return AnomalyDetectorType.valueOf(getMethodName().toUpperCase());
    }

    /**
     * Evaluates a telemetry observation against a sequence of historical baseline values.
     *
     * @param resourceId        ID of the resource under evaluation
     * @param metricName        Name of the metric
     * @param currentValue      The observation value to evaluate
     * @param historicalSamples The historical observation values used as baseline
     * @param threshold         Algorithm-specific sensitivity threshold
     * @param minSampleCount    Minimum required historical samples
     * @param evaluatedAt       Timestamp associated with the evaluation
     * @return Full anomaly detection response
     */
    AnomalyDetectionResponse evaluate(
            UUID resourceId,
            String metricName,
            double currentValue,
            List<Double> historicalSamples,
            double threshold,
            int minSampleCount,
            Instant evaluatedAt
    );
}
