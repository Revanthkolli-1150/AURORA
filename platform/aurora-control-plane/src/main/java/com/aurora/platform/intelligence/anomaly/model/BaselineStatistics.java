package com.aurora.platform.intelligence.anomaly.model;

/**
 * Encapsulates statistical baseline metrics calculated across historical telemetry observations.
 */
public record BaselineStatistics(
        int sampleCount,
        Double mean,
        Double standardDeviation,
        Double minimum,
        Double maximum
) {
    public static BaselineStatistics empty() {
        return new BaselineStatistics(0, null, null, null, null);
    }
}
