package com.aurora.platform.intelligence.anomaly.detector;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ZScoreAnomalyDetectorTest {

    private ZScoreAnomalyDetector detector;
    private UUID resourceId;
    private Instant now;

    @BeforeEach
    void setUp() {
        detector = new ZScoreAnomalyDetector();
        resourceId = UUID.randomUUID();
        now = Instant.now();
    }

    @Test
    @DisplayName("1. Normal value: value within 3 standard deviations should be NORMAL")
    void shouldClassifyNormalValueCorrectly() {
        // Baseline: 10 points with mean = 50.0, variance = 4.0, stdDev = 2.0
        // Values: 48, 52, 49, 51, 50, 50, 48, 52, 49, 51
        List<Double> baseline = List.of(48.0, 52.0, 49.0, 51.0, 50.0, 50.0, 48.0, 52.0, 49.0, 51.0);
        double currentValue = 52.0; // z = (52 - 50) / 1.563 = 1.28 < 3.0

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "cpu_usage", currentValue, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
        assertThat(response.zScore()).isNotNull();
        assertThat(Math.abs(response.zScore())).isLessThan(3.0);
        assertThat(response.anomalyScore()).isLessThan(1.0);
        assertThat(response.anomalyScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("2. Strong positive anomaly: value far above mean should be ANOMALOUS")
    void shouldClassifyStrongPositiveAnomaly() {
        // Baseline: 16 points around 44%
        List<Double> baseline = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0,
                44.0, 43.0, 45.0, 44.0, 46.0, 43.0, 44.0, 45.0);
        double currentValue = 91.0;

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "cpu_usage", currentValue, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isNotNull();
        assertThat(response.zScore()).isGreaterThan(3.0);
        assertThat(response.anomalyScore()).isLessThan(1.0);
        assertThat(response.anomalyScore()).isGreaterThan(0.95);
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-response.zScore() / 3.0), within(1e-6));
    }

    @Test
    @DisplayName("3. Strong negative anomaly: value far below mean should be ANOMALOUS")
    void shouldClassifyStrongNegativeAnomaly() {
        List<Double> baseline = List.of(100.0, 102.0, 98.0, 101.0, 99.0, 100.0, 101.0, 99.0, 102.0, 98.0);
        double currentValue = -20.0;

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "temperature", currentValue, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isNotNull();
        assertThat(response.zScore()).isLessThan(-3.0);
        assertThat(response.anomalyScore()).isLessThan(1.0);
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-Math.abs(response.zScore()) / 3.0), within(1e-6));
    }

    @Test
    @DisplayName("4. Exactly threshold: |z| == threshold should be classified as ANOMALOUS")
    void shouldClassifyExactThresholdAsAnomalous() {
        // Construct baseline with known mean = 10.0 and sample stdDev = 2.0
        // For sample count 10:
        // Values: 8, 12, 8, 12, 8, 12, 8, 12, 10, 10 -> sum = 100, mean = 10.0
        // (8-10)^2 = 4 (4 times = 16), (12-10)^2 = 4 (4 times = 16), (10-10)^2 = 0 -> sum diff^2 = 32
        // Sample variance = 32 / (10 - 1) = 32/9. Sample stdDev = sqrt(32/9) = 1.885618
        // Let's use exact currentValue = mean + 3.0 * stdDev
        List<Double> baseline = List.of(10.0, 12.0, 8.0, 14.0, 6.0, 10.0, 12.0, 8.0, 14.0, 6.0);
        // sum = 100, mean = 10.0
        // sum of squared diffs: 0 + 4 + 4 + 16 + 16 + 0 + 4 + 4 + 16 + 16 = 80
        // sample stdDev = sqrt(80 / 9)
        double stdDev = Math.sqrt(80.0 / 9.0);
        double currentValue = 10.0 + (3.0 * stdDev);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "test_metric", currentValue, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isCloseTo(3.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.632, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-1.0), within(1e-6));
        assertThat(response.anomalyScore()).isLessThan(1.0);
    }

    @Test
    @DisplayName("5. Insufficient samples: baseline with fewer than minSampleCount returns INSUFFICIENT_DATA")
    void shouldReturnInsufficientDataWhenFewerThanMinSamples() {
        List<Double> baseline = List.of(42.0, 43.0, 44.0, 45.0, 46.0); // 5 samples < 10

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "cpu_usage", 99.0, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.INSUFFICIENT_DATA);
        assertThat(response.sampleCount()).isEqualTo(5);
        assertThat(response.zScore()).isNull();
        assertThat(response.anomalyScore()).isEqualTo(0.0);
        assertThat(response.mean()).isEqualTo(44.0);
        assertThat(response.minimum()).isEqualTo(42.0);
        assertThat(response.maximum()).isEqualTo(46.0);
    }

    @Test
    @DisplayName("6. Zero standard deviation with same current value returns NORMAL")
    void shouldReturnNormalWhenZeroStdDevAndValueMatches() {
        List<Double> baseline = List.of(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "constant_metric", 50.0, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
        assertThat(response.standardDeviation()).isEqualTo(0.0);
        assertThat(response.zScore()).isEqualTo(0.0);
        assertThat(response.anomalyScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("7. Zero standard deviation with different current value returns ANOMALOUS with no division by zero")
    void shouldReturnAnomalousWhenZeroStdDevAndValueDiffers() {
        List<Double> baseline = List.of(50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0, 50.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "constant_metric", 55.0, baseline, 3.0, 10, now);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.standardDeviation()).isEqualTo(0.0);
        assertThat(response.zScore()).isNull(); // Division by zero avoided
        assertThat(response.anomalyScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("8 & 9. Correct sample mean and sample standard deviation calculations")
    void shouldCalculateCorrectSampleMeanAndStandardDeviation() {
        // Deterministic dataset: [10, 20, 30, 40, 50, 60, 70, 80, 90, 100]
        // Mean = 55.0
        // Sample variance (denominator N-1 = 9) = 8250 / 9 = 916.66667
        // Sample stdDev = sqrt(916.66667) = 30.2765035
        List<Double> baseline = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "metric", 55.0, baseline, 3.0, 10, now);

        assertThat(response.sampleCount()).isEqualTo(10);
        assertThat(response.mean()).isEqualTo(55.0);
        assertThat(response.standardDeviation()).isCloseTo(30.2765035, within(1e-6));
        assertThat(response.minimum()).isEqualTo(10.0);
        assertThat(response.maximum()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("10 & 11. Correct sample count, minimum, and maximum")
    void shouldTrackCorrectSampleCountMinAndMax() {
        List<Double> baseline = List.of(15.0, 3.0, 88.0, 42.0, 19.0, 7.0, 64.0, 12.0, 33.0, 25.0, 99.0);

        AnomalyDetectionResponse response = detector.evaluate(
                resourceId, "metric", 40.0, baseline, 3.0, 10, now);

        assertThat(response.sampleCount()).isEqualTo(11);
        assertThat(response.minimum()).isEqualTo(3.0);
        assertThat(response.maximum()).isEqualTo(99.0);
    }

    @Test
    @DisplayName("14. No NaN or Infinity output under any edge conditions")
    void shouldNeverProduceNaNOrInfinity() {
        // Zero variance
        AnomalyDetectionResponse res1 = detector.evaluate(
                resourceId, "m", 10.0, List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), 3.0, 10, now);
        if (res1.zScore() != null) {
            assertThat(Double.isNaN(res1.zScore())).isFalse();
            assertThat(Double.isInfinite(res1.zScore())).isFalse();
        }
        assertThat(Double.isNaN(res1.anomalyScore())).isFalse();
        assertThat(Double.isInfinite(res1.anomalyScore())).isFalse();

        // Empty list
        AnomalyDetectionResponse res2 = detector.evaluate(
                resourceId, "m", 10.0, List.of(), 3.0, 10, now);
        assertThat(res2.zScore()).isNull();
        assertThat(res2.anomalyScore()).isEqualTo(0.0);
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 1C Targeted Correction: Anomaly Score Normalization / Saturation Fix Tests (Items 1-11)
    // Formula: anomalyScore = 1 - exp(-abs(zScore) / threshold)
    // Bounded: 0.0 <= anomalyScore < 1.0 for finite z. For z=0, anomalyScore = 0.0.
    // ---------------------------------------------------------------------------------------------

    private static final List<Double> CONTROLLED_BASELINE = List.of(
            9.0, 11.0, 9.0, 11.0, 9.0, 11.0, 9.0, 11.0, 10.0, 10.0
    );
    private static final double BASELINE_MEAN = 10.0;
    private static final double BASELINE_STD_DEV = Math.sqrt(8.0 / 9.0);

    private AnomalyDetectionResponse evaluateWithZScore(double z, double threshold) {
        double currentValue = BASELINE_MEAN + (z * BASELINE_STD_DEV);
        return detector.evaluate(resourceId, "metric", currentValue, CONTROLLED_BASELINE, threshold, 10, now);
    }

    @Test
    @DisplayName("Targeted 1. Zero z-score yields anomalyScore = 0.0")
    void shouldProduceZeroAnomalyScoreForZeroZScore() {
        AnomalyDetectionResponse response = evaluateWithZScore(0.0, 3.0);

        assertThat(response.zScore()).isCloseTo(0.0, within(1e-6));
        assertThat(response.anomalyScore()).isEqualTo(0.0);
        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
    }

    @Test
    @DisplayName("Targeted 2. Moderate anomaly (z=1, threshold=3) yields anomalyScore approximately 0.283")
    void shouldProduceModerateAnomalyScoreForZ1() {
        AnomalyDetectionResponse response = evaluateWithZScore(1.0, 3.0);

        assertThat(response.zScore()).isCloseTo(1.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.283, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-1.0 / 3.0), within(1e-6));
        assertThat(response.status()).isEqualTo(AnomalyStatus.NORMAL);
    }

    @Test
    @DisplayName("Targeted 3. Threshold-level anomaly (z=3, threshold=3) yields anomalyScore approximately 0.632")
    void shouldProduceThresholdLevelAnomalyScoreForZ3() {
        AnomalyDetectionResponse response = evaluateWithZScore(3.0, 3.0);

        assertThat(response.zScore()).isCloseTo(3.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.632, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-1.0), within(1e-6));
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("Targeted 4. Strong anomaly (z=6, threshold=3) yields anomalyScore approximately 0.865")
    void shouldProduceStrongAnomalyScoreForZ6() {
        AnomalyDetectionResponse response = evaluateWithZScore(6.0, 3.0);

        assertThat(response.zScore()).isCloseTo(6.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.865, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-2.0), within(1e-6));
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("Targeted 5. Critical-boundary anomaly (z=9, threshold=3) yields anomalyScore approximately 0.950")
    void shouldProduceCriticalBoundaryAnomalyScoreForZ9() {
        AnomalyDetectionResponse response = evaluateWithZScore(9.0, 3.0);

        assertThat(response.zScore()).isCloseTo(9.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(0.950, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-3.0), within(1e-6));
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("Targeted 6. Extreme anomaly (z=45, threshold=3) yields score close to 1.0 but strictly below 1.0")
    void shouldProduceExtremeAnomalyScoreBelowOneForZ45() {
        AnomalyDetectionResponse response = evaluateWithZScore(45.0, 3.0);

        assertThat(response.zScore()).isCloseTo(45.0, within(1e-6));
        assertThat(response.anomalyScore()).isCloseTo(1.0, within(0.001));
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-15.0), within(1e-6));
        assertThat(response.anomalyScore()).isLessThan(1.0);
        assertThat(response.anomalyScore()).isNotEqualTo(1.0);
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("Targeted 7. Negative z-score (z=-6) yields identical anomalyScore as z=+6")
    void shouldProduceIdenticalScoreForNegativeAndPositiveZScores() {
        AnomalyDetectionResponse posResponse = evaluateWithZScore(6.0, 3.0);
        AnomalyDetectionResponse negResponse = evaluateWithZScore(-6.0, 3.0);

        assertThat(negResponse.zScore()).isCloseTo(-6.0, within(1e-6));
        assertThat(negResponse.anomalyScore()).isCloseTo(posResponse.anomalyScore(), within(1e-6));
        assertThat(negResponse.anomalyScore()).isCloseTo(0.865, within(0.001));
        assertThat(negResponse.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("Targeted 8. Monotonicity: score(z=1) < score(z=3) < score(z=6) < score(z=9) < score(z=15)")
    void shouldVerifyMonotonicityAcrossIncreasingZScores() {
        double score1 = evaluateWithZScore(1.0, 3.0).anomalyScore();
        double score3 = evaluateWithZScore(3.0, 3.0).anomalyScore();
        double score6 = evaluateWithZScore(6.0, 3.0).anomalyScore();
        double score9 = evaluateWithZScore(9.0, 3.0).anomalyScore();
        double score15 = evaluateWithZScore(15.0, 3.0).anomalyScore();

        assertThat(score1).isLessThan(score3);
        assertThat(score3).isLessThan(score6);
        assertThat(score6).isLessThan(score9);
        assertThat(score9).isLessThan(score15);
    }

    @Test
    @DisplayName("Targeted 9. Bounds: for representative finite z-scores 0.0 <= score < 1.0")
    void shouldVerifyBoundsForRepresentativeFiniteZScores() {
        double[] testZValues = {0.0, 0.1, 0.5, 1.0, 2.0, 3.0, 5.0, 6.0, 9.0, 15.0, 45.0, 100.0, 500.0, 2000.0};
        for (double z : testZValues) {
            AnomalyDetectionResponse res = evaluateWithZScore(z, 3.0);
            assertThat(res.anomalyScore())
                    .as("Anomaly score for z=" + z + " must be >= 0.0")
                    .isGreaterThanOrEqualTo(0.0);
            assertThat(res.anomalyScore())
                    .as("Anomaly score for z=" + z + " must remain strictly < 1.0")
                    .isLessThan(1.0);
        }
    }

    @Test
    @DisplayName("Targeted 10. Severity mapping: score partitions into LOW, MEDIUM, HIGH, CRITICAL")
    void shouldVerifySeverityMappingAlignment() {
        // Phase 1D severity mapping rules:
        // score < 0.50        -> LOW
        // 0.50 <= score < 0.80 -> MEDIUM
        // 0.80 <= score < 0.95 -> HIGH
        // score >= 0.95       -> CRITICAL

        AnomalyDetectionResponse resLow = evaluateWithZScore(1.0, 3.0);      // score ~ 0.283
        AnomalyDetectionResponse resMedium = evaluateWithZScore(3.0, 3.0);   // score ~ 0.632
        AnomalyDetectionResponse resHigh = evaluateWithZScore(6.0, 3.0);     // score ~ 0.865
        AnomalyDetectionResponse resCritical = evaluateWithZScore(9.0, 3.0); // score ~ 0.950
        AnomalyDetectionResponse resExtreme = evaluateWithZScore(45.0, 3.0); // score ~ 0.999999

        assertThat(resLow.anomalyScore()).isLessThan(0.50);
        assertThat(resMedium.anomalyScore()).isGreaterThanOrEqualTo(0.50).isLessThan(0.80);
        assertThat(resHigh.anomalyScore()).isGreaterThanOrEqualTo(0.80).isLessThan(0.95);
        assertThat(resCritical.anomalyScore()).isGreaterThanOrEqualTo(0.95).isLessThan(1.0);
        assertThat(resExtreme.anomalyScore()).isGreaterThanOrEqualTo(0.95).isLessThan(1.0);
    }

    @Test
    @DisplayName("Targeted 11. Regression: classification rules remain unchanged (NORMAL, ANOMALOUS, INSUFFICIENT_DATA)")
    void shouldPreserveAnomalyClassificationRules() {
        // NORMAL remains NORMAL
        AnomalyDetectionResponse normalRes = evaluateWithZScore(1.5, 3.0);
        assertThat(normalRes.status()).isEqualTo(AnomalyStatus.NORMAL);
        assertThat(normalRes.anomalyScore()).isLessThan(0.50);

        // ANOMALOUS remains ANOMALOUS
        AnomalyDetectionResponse anomalousRes = evaluateWithZScore(3.0, 3.0);
        assertThat(anomalousRes.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(anomalousRes.anomalyScore()).isGreaterThanOrEqualTo(0.50);

        // INSUFFICIENT_DATA remains INSUFFICIENT_DATA
        AnomalyDetectionResponse insufficientRes = detector.evaluate(
                resourceId, "metric", 10.0, List.of(10.0, 11.0, 9.0), 3.0, 10, now);
        assertThat(insufficientRes.status()).isEqualTo(AnomalyStatus.INSUFFICIENT_DATA);
        assertThat(insufficientRes.anomalyScore()).isEqualTo(0.0);
    }
}
