package com.aurora.platform.intelligence.anomaly.detector;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyDetectorType;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MadAnomalyDetectorTest {

    private MadAnomalyDetector detector;
    private UUID resourceId;
    private Instant now;

    // Controlled baseline of 10 points with known median = 10.0 and MAD = 1.0
    // Sorted: 7, 8, 9, 10, 10, 10, 11, 11, 12, 13
    // Median: (10 + 10) / 2 = 10.0
    // Absolute deviations: 3, 2, 1, 0, 0, 0, 1, 1, 2, 3 -> sorted: 0, 0, 0, 1, 1, 1, 2, 2, 3, 3
    // MAD: (1 + 1) / 2 = 1.0
    // Robust scale: 1.4826 * 1.0 = 1.4826
    private static final List<Double> CONTROLLED_BASELINE = List.of(
            7.0, 8.0, 9.0, 10.0, 10.0, 10.0, 11.0, 11.0, 12.0, 13.0
    );
    private static final double MEDIAN = 10.0;
    private static final double ROBUST_SCALE = 1.4826;

    @BeforeEach
    void setUp() {
        detector = new MadAnomalyDetector();
        resourceId = UUID.randomUUID();
        now = Instant.now();
    }

    private AnomalyDetectionResponse evaluateWithRobustZ(double robustZ, double threshold) {
        double currentValue = MEDIAN + (robustZ * ROBUST_SCALE);
        return detector.evaluate(resourceId, "test_metric", currentValue, CONTROLLED_BASELINE, threshold, 10, now);
    }

    @Test
    @DisplayName("1. Normal value around median returns NORMAL status and low score")
    void shouldClassifyNormalValueAroundMedian() {
        AnomalyDetectionResponse response = evaluateWithRobustZ(0.0, 3.0);

        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
        assertThat(response.zScore()).isNotNull();
        assertThat(response.zScore()).isCloseTo(0.0, within(1e-6));
        assertThat(response.anomalyScore()).isEqualTo(0.0);
        assertThat(response.detectionMethod()).isEqualTo("MAD");
        assertThat(response.sampleCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("2. Moderate deviation below threshold returns NORMAL status")
    void shouldClassifyModerateDeviationBelowThreshold() {
        // robustZ = 1.0 < threshold (3.0)
        AnomalyDetectionResponse response = evaluateWithRobustZ(1.0, 3.0);

        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
        assertThat(response.zScore()).isCloseTo(1.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.283, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-1.0 / 3.0), within(1e-6));
        assertThat(response.anomalyScore()).isLessThan(0.50); // Maps to LOW severity
    }

    @Test
    @DisplayName("3. Anomaly at threshold (robustZ == threshold) returns ANOMALOUS")
    void shouldClassifyAnomalyAtThreshold() {
        // robustZ = 3.0 == threshold (3.0)
        AnomalyDetectionResponse response = evaluateWithRobustZ(3.0, 3.0);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isCloseTo(3.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.632, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-1.0), within(1e-6));
    }

    @Test
    @DisplayName("4. Strong anomaly above threshold returns ANOMALOUS and high score")
    void shouldClassifyStrongAnomalyAboveThreshold() {
        // robustZ = 6.0 > threshold (3.0)
        AnomalyDetectionResponse response = evaluateWithRobustZ(6.0, 3.0);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isCloseTo(6.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.865, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-2.0), within(1e-6));
    }

    @Test
    @DisplayName("5. Zero MAD with current == median returns NORMAL, robustZ = 0, anomalyScore = 0")
    void shouldHandleZeroMadWhenCurrentMatchesMedian() {
        List<Double> constantBaseline = List.of(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "constant_metric", 50.0, constantBaseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
        assertThat(response.zScore()).isEqualTo(0.0);
        assertThat(response.anomalyScore()).isEqualTo(0.0);
        assertThat(response.detectionMethod()).isEqualTo("MAD");
    }

    @Test
    @DisplayName("6. Zero MAD with current != median returns ANOMALOUS, robustZ = null, anomalyScore = 1.0")
    void shouldHandleZeroMadWhenCurrentDiffersFromMedian() {
        List<Double> constantBaseline = List.of(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "constant_metric", 55.0, constantBaseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isNull(); // Division by zero avoided, standardized score undefined
        assertThat(response.anomalyScore()).isEqualTo(1.0);
        assertThat(response.detectionMethod()).isEqualTo("MAD");
    }

    @Test
    @DisplayName("7. Symmetry around median: robustZ and anomalyScore are identical for positive and negative deviations")
    void shouldExhibitSymmetryAroundMedian() {
        AnomalyDetectionResponse posResponse = detector.evaluate(
                resourceId, "metric", MEDIAN + (4.0 * ROBUST_SCALE), CONTROLLED_BASELINE, 3.0, 10, now);
        AnomalyDetectionResponse negResponse = detector.evaluate(
                resourceId, "metric", MEDIAN - (4.0 * ROBUST_SCALE), CONTROLLED_BASELINE, 3.0, 10, now);

        assertThat(posResponse.zScore()).isCloseTo(4.0, within(1e-6));
        assertThat(negResponse.zScore()).isCloseTo(4.0, within(1e-6));
        assertThat(negResponse.anomalyScore()).isCloseTo(posResponse.anomalyScore(), within(1e-6));
        assertThat(posResponse.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(negResponse.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("8. Monotonicity of robust anomaly score across increasing deviations")
    void shouldVerifyMonotonicityOfAnomalyScore() {
        double score1 = evaluateWithRobustZ(1.0, 3.0).anomalyScore();
        double score3 = evaluateWithRobustZ(3.0, 3.0).anomalyScore();
        double score6 = evaluateWithRobustZ(6.0, 3.0).anomalyScore();
        double score9 = evaluateWithRobustZ(9.0, 3.0).anomalyScore();
        double score15 = evaluateWithRobustZ(15.0, 3.0).anomalyScore();

        assertThat(score1).isLessThan(score3);
        assertThat(score3).isLessThan(score6);
        assertThat(score6).isLessThan(score9);
        assertThat(score9).isLessThan(score15);
    }

    @Test
    @DisplayName("9. Score bounds: 0.0 <= score < 1.0 for finite non-zero MAD cases")
    void shouldVerifyScoreBoundsForFiniteNonZeroMad() {
        double[] testRobustZs = {0.0, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 6.0, 9.0, 15.0, 45.0, 100.0, 500.0, 2000.0};
        for (double z : testRobustZs) {
            AnomalyDetectionResponse res = evaluateWithRobustZ(z, 3.0);
            assertThat(res.anomalyScore())
                    .as("Score for robustZ=" + z + " must be >= 0.0")
                    .isGreaterThanOrEqualTo(0.0);
            assertThat(res.anomalyScore())
                    .as("Score for robustZ=" + z + " must remain strictly < 1.0")
                    .isLessThan(1.0);
        }
    }

    @Test
    @DisplayName("10. Extreme finite value: score is close to 1.0 but strictly below 1.0")
    void shouldHandleExtremeFiniteValueWithoutCollapsingToOne() {
        AnomalyDetectionResponse response = evaluateWithRobustZ(45.0, 3.0);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isCloseTo(45.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(1.0, within(0.001));
        assertThat(response.anomalyScore()).isLessThan(1.0);
        assertThat(response.anomalyScore()).isNotEqualTo(1.0);
    }

    @Test
    @DisplayName("11. Insufficient historical data returns INSUFFICIENT_DATA status and score = 0")
    void shouldReturnInsufficientDataWhenBelowMinSamples() {
        // Less than 10 samples
        List<Double> shortBaseline = List.of(10.0, 11.0, 9.0, 10.0, 12.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "metric", 15.0, shortBaseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.INSUFFICIENT_DATA);
        assertThat(response.sampleCount()).isEqualTo(5);
        assertThat(response.zScore()).isNull();
        assertThat(response.anomalyScore()).isEqualTo(0.0);
        assertThat(response.detectionMethod()).isEqualTo("MAD");

        // Empty baseline
        AnomalyDetectionResponse emptyResponse = detector.evaluate(
                resourceId, "metric", 15.0, List.of(), 3.0, 10, now);

        assertThat(emptyResponse.status()).isEqualTo(AnomalyStatus.INSUFFICIENT_DATA);
        assertThat(emptyResponse.sampleCount()).isEqualTo(0);
        assertThat(emptyResponse.anomalyScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("12. Detection method identifier equals MAD")
    void shouldIdentifyDetectionMethodAsMAD() {
        assertThat(detector.getMethodName()).isEqualTo("MAD");
        assertThat(detector.getType()).isEqualTo(AnomalyDetectorType.MAD);

        AnomalyDetectionResponse response = evaluateWithRobustZ(1.0, 3.0);
        assertThat(response.detectionMethod()).isEqualTo("MAD");
    }

    @Test
    @DisplayName("Robustness to historical outlier: MAD is resistant while standard deviation is inflated")
    void shouldDemonstrateRobustnessAgainstHistoricalOutlier() {
        // Normal baseline with 1 extreme historical outlier (100.0)
        List<Double> baselineWithOutlier = List.of(
                10.0, 10.0, 10.0, 10.0, 10.0, 11.0, 11.0, 12.0, 12.0, 100.0
        );
        // Clean median is 10.0. MAD remains small (~1.0).
        // A new observation of 16.0 will be detected as ANOMALOUS by MAD,
        // whereas standard deviation would be heavily inflated by the 100.0 outlier.
        AnomalyDetectionResponse madResponse = detector.evaluate(
                resourceId, "metric", 16.0, baselineWithOutlier, 3.0, 10, now);

        assertThat(madResponse.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(madResponse.zScore()).isGreaterThan(3.0);
        assertThat(madResponse.detectionMethod()).isEqualTo("MAD");
    }
}
