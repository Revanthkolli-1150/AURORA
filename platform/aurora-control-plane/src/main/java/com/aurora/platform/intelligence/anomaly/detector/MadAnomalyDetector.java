package com.aurora.platform.intelligence.anomaly.detector;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic, robust statistical anomaly detector utilizing Median Absolute Deviation (MAD).
 *
 * <p>Mathematical definitions:
 * <ul>
 *   <li>Median: $\tilde{x} = \text{median}(X)$</li>
 *   <li>Absolute Deviation: $d_i = |x_i - \tilde{x}|$</li>
 *   <li>Median Absolute Deviation: $\text{MAD} = \text{median}(\{d_1, \dots, d_N\})$</li>
 *   <li>Consistency Constant: $C = 1.4826$ (standard asymptotic normal consistency factor)</li>
 *   <li>Robust Scale: $\hat{\sigma}_{\text{MAD}} = 1.4826 \times \text{MAD}$</li>
 *   <li>Robust Standardized Deviation: $\text{robustZ} = \frac{|x - \tilde{x}|}{\hat{\sigma}_{\text{MAD}}}$</li>
 *   <li>Anomaly Condition: $\text{robustZ} \ge \text{threshold}$</li>
 *   <li>Anomaly Score: $1 - \exp\left(-\frac{\text{robustZ}}{\text{threshold}}\right)$
 *       representing normalized anomaly magnitude (not probability of anomaly),
 *       bounded in $[0.0, 1.0)$ for finite non-zero MAD.</li>
 * </ul>
 *
 * <p>Zero-MAD Semantics:
 * When $\text{MAD} = 0$ (such as constant invariant series or series where >50% of values are identical):
 * <ul>
 *   <li>If $x = \tilde{x}$: Returns {@code NORMAL}, $\text{robustZ} = 0.0$, $\text{score} = 0.0$.</li>
 *   <li>If $x \neq \tilde{x}$: Returns {@code ANOMALOUS}, $\text{robustZ} = \text{null}$, $\text{score} = 1.0$.
 *       $\text{robustZ}$ is mathematically undefined when robust scale is zero because dividing by zero
 *       is indeterminate/infinite. Returning {@code null} prevents {@code Double.NaN} and {@code Double.POSITIVE_INFINITY}.</li>
 * </ul>
 */
@Component
public class MadAnomalyDetector implements AnomalyDetector {

    public static final String METHOD_NAME = "MAD";
    public static final double CONSISTENCY_CONSTANT = 1.4826;
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

        List<Double> cleanSamples = new ArrayList<>();
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
        double mean = sampleCount > 0 ? (sum / sampleCount) : 0.0;

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
                    mean,
                    null,
                    min,
                    max,
                    evaluatedAt
            );
        }

        // Calculate sample median
        double median = calculateMedian(cleanSamples);

        // Calculate absolute deviations from median
        List<Double> absoluteDeviations = new ArrayList<>(sampleCount);
        for (double val : cleanSamples) {
            absoluteDeviations.add(Math.abs(val - median));
        }

        // Calculate MAD
        double mad = calculateMedian(absoluteDeviations);
        double robustScale = CONSISTENCY_CONSTANT * mad;

        // Edge case: Zero MAD (invariant historical baseline or >50% identical values)
        if (robustScale < EPSILON) {
            boolean isConstantMatch = Math.abs(currentValue - median) < EPSILON;
            AnomalyStatus status = isConstantMatch ? AnomalyStatus.NORMAL : AnomalyStatus.ANOMALOUS;
            Double robustZ = isConstantMatch ? 0.0 : null;
            double anomalyScore = isConstantMatch ? 0.0 : 1.0;

            return new AnomalyDetectionResponse(
                    resourceId,
                    metricName,
                    currentValue,
                    getMethodName(),
                    status,
                    anomalyScore,
                    robustZ,
                    threshold,
                    sampleCount,
                    mean,
                    0.0,
                    min,
                    max,
                    evaluatedAt
            );
        }

        // Standard robust standardized deviation calculation: robustZ = abs(currentValue - median) / robustScale
        double robustZ = Math.abs(currentValue - median) / robustScale;
        if (Double.isNaN(robustZ) || Double.isInfinite(robustZ)) {
            robustZ = 0.0;
        }

        // Account for floating-point rounding precision at exact threshold boundary
        boolean isAnomalous = (robustZ >= threshold - EPSILON);
        AnomalyStatus status = isAnomalous ? AnomalyStatus.ANOMALOUS : AnomalyStatus.NORMAL;

        // Normalized anomaly magnitude: anomalyScore = 1 - exp(-robustZ / threshold)
        // Result remains bounded in [0.0, 1.0) for finite robustZ.
        // Documented explicitly as "normalized anomaly magnitude", rather than "probability of anomaly".
        double anomalyScore = 1.0 - Math.exp(-robustZ / threshold);
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
                robustZ,
                threshold,
                sampleCount,
                mean,
                robustScale,
                min,
                max,
                evaluatedAt
        );
    }

    /**
     * Calculates the median of a list of values.
     */
    public static double calculateMedian(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        } else {
            return (sorted.get((n - 1) / 2) + sorted.get(n / 2)) / 2.0;
        }
    }
}
