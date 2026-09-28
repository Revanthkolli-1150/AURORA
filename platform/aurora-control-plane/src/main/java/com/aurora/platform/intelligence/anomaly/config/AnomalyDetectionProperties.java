package com.aurora.platform.intelligence.anomaly.config;

import com.aurora.platform.intelligence.anomaly.model.AnomalyDetectorType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aurora.intelligence.anomaly")
@Getter
@Setter
public class AnomalyDetectionProperties {

    /**
     * Default anomaly threshold for classifying a metric observation as anomalous (default: 3.0).
     */
    private double defaultThreshold = 3.0;

    /**
     * Minimum historical sample count required before evaluating an observation.
     * Baselines with fewer samples will yield INSUFFICIENT_DATA (default: 10).
     */
    private int minSampleCount = 10;

    /**
     * Configured anomaly detector algorithm: "Z_SCORE" or "MAD" (default: "Z_SCORE").
     */
    private String detector = "Z_SCORE";

    public AnomalyDetectorType getDetectorType() {
        if (detector == null || detector.isBlank()) {
            return AnomalyDetectorType.Z_SCORE;
        }
        try {
            return AnomalyDetectorType.valueOf(detector.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid anomaly detector configured: '" + detector + "'. Valid values are: Z_SCORE, MAD", e);
        }
    }

    public void setDetector(String detector) {
        if (detector != null && !detector.isBlank()) {
            try {
                AnomalyDetectorType.valueOf(detector.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid anomaly detector configured: '" + detector + "'. Valid values are: Z_SCORE, MAD", e);
            }
        }
        this.detector = detector;
    }
}
