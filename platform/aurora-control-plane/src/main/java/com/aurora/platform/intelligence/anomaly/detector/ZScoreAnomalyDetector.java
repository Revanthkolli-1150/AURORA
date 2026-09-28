package com.aurora.platform.intelligence.anomaly.detector;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic statistical anomaly detector utilizing Z-Score and sample standard deviation.
 *
 * <p>Mathematical definitions:
 * <ul>
 *   <li>Mean: $\mu = \frac{1}{N}\sum_{i=1}^N x_i$</li>
 *   <li>Sample Standard Deviation: $s = \sqrt{\frac{1}{N-1}\sum_{i=1}^N (x_i - \mu)^2}$</li>
 *   <li>Z-Score: $z = \frac{x - \mu}{s}$</li>
 *   <li>Anomaly Condition: $|z| \ge \text{threshold}$</li>
 *   <li>Anomaly Score: $1 - \exp\left(-\frac{|z|}{\text{threshold}}\right)$ (normalized anomaly magnitude, not probability, bounded in $[0.0, 1.0)$ for finite $z$)</li>
 * </ul>
 */
@Component
@Primary
public class ZScoreAnomalyDetector implements AnomalyDetector {

    public static final String METHOD_NAME = "Z_SCORE";
    private static final double EPSILON = 1e-9;

    @Override
    public String getMethodName() {
        return METHOD_NAME;
    }

    @Override
    public AnomalyDetectionResponse evaluate(
            UUID resourceId,
            String metricName,
            double currentValue,
            List<Double> historicalSamples,
            double threshold,
            int minSampleCount,
            Instant evaluatedAt) {

        if (historicalSamples == null || historicalSamples.isEmpty()) {
            return new AnomalyDetectionResponse(
                    resourceId,
                    metricName,
                    currentValue,
                    getMethodName(),
                    AnomalyStatus.INSUFFICIENT_DATA,
                    0.0,
                    null,
                    threshold,
                    0,
                    null,
                    null,
                    null,
                    null,
                    evaluatedAt
            );
        }

        if (Double.isNaN(currentValue) || Double.isInfinite(currentValue)) {
            return new AnomalyDetectionResponse(
                    resourceId,
                    metricName,
                    currentValue,
                    getMethodName(),
                    AnomalyStatus.INSUFFICIENT_DATA,
                    0.0,
                    null,
                    threshold,
                    0,
                    null,
                    null,
                    null,
                    null,
                    evaluatedAt
            );
        }

        java.util.List<Double> cleanSamples = new java.util.ArrayList<>();
        double sum = 0.0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Double sample : historicalSamples) {
            if (sample != null && !Double.isNaN(sample) && !Double.isInfinite(sample)) {
                cleanSamples.add(sample);
                sum += sample;
                if (sample < min) min = sample;
                if (sample > max) max = sample;
            }
        }

        int sampleCount = cleanSamples.size();

        // Check for insufficient sample count
        if (sampleCount < minSampleCount) {
            return new AnomalyDetectionResponse(
                    resourceId,
                    metricName,
                    currentValue,
                    getMethodName(),
                    AnomalyStatus.INSUFFICIENT_DATA,
                    0.0,
                    null,
                    threshold,
                    sampleCount,
                    sampleCount > 0 ? (sum / sampleCount) : null,
                    null,
                    sampleCount > 0 ? min : null,
                    sampleCount > 0 ? max : null,
                    evaluatedAt
            );
        }

        double mean = sum / sampleCount;

        // Calculate sample variance and sample standard deviation (N - 1 denominator)
        double sumSquaredDiffs = 0.0;
        for (Double val : cleanSamples) {
            double diff = val - mean;
            sumSquaredDiffs += diff * diff;
        }
        double stdDev = Math.sqrt(sumSquaredDiffs / (sampleCount - 1));

        // Edge case: Zero standard deviation (invariant historical baseline)
        if (stdDev < EPSILON) {
            boolean isConstantMatch = Math.abs(currentValue - mean) < EPSILON;
            AnomalyStatus status = isConstantMatch ? AnomalyStatus.NORMAL : AnomalyStatus.ANOMALOUS;
            Double zScore = isConstantMatch ? 0.0 : null;
            double anomalyScore = isConstantMatch ? 0.0 : 1.0;

            return new AnomalyDetectionResponse(
                    resourceId,
                    metricName,
                    currentValue,
                    getMethodName(),
                    status,
                    anomalyScore,
                    zScore,
                    threshold,
                    sampleCount,
                    mean,
                    stdDev,
                    min,
                    max,
                    evaluatedAt
            );
        }

        // Standard Z-Score evaluation
        double z = (currentValue - mean) / stdDev;
        if (Double.isNaN(z) || Double.isInfinite(z)) {
            z = 0.0;
        }

        // Account for floating-point rounding precision at exact threshold boundary
        boolean isAnomalous = (Math.abs(z) >= threshold - EPSILON);
        AnomalyStatus status = isAnomalous ? AnomalyStatus.ANOMALOUS : AnomalyStatus.NORMAL;

        // Normalized anomaly magnitude: anomalyScore = 1 - exp(-abs(zScore) / threshold)
        // Result remains bounded in [0.0, 1.0) for finite z. For z=0, anomalyScore = 0.0.
        // Documented explicitly as "normalized anomaly magnitude", rather than "probability of anomaly".
        double anomalyScore = 1.0 - Math.exp(-Math.abs(z) / threshold);
        if (anomalyScore >= 1.0) {
            anomalyScore = Math.nextDown(1.0);
        } else if (anomalyScore < 0.0) {
            anomalyScore = 0.0;
        }

        return new AnomalyDetectionResponse(
                resourceId,
                metricName,
                currentValue,
                getMethodName(),
                status,
                anomalyScore,
                z,
                threshold,
                sampleCount,
                mean,
                stdDev,
                min,
                max,
                evaluatedAt
        );
    }
}
