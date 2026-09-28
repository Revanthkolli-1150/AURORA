package com.aurora.platform.intelligence.graph.domain;

import com.aurora.platform.intelligence.rca.application.EdgeWeightResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmpiricalEdgeWeightCalculatorTest {

    @Test
    @DisplayName("Requirement 1: Zero history (N_incident = 0) returns prior 0.20 with fallback=true")
    void testZeroHistoryReturnsPrior() {
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(0L, 0L);

        assertThat(result.finalWeight()).isEqualTo(0.20);
        assertThat(result.rawWeight()).isEqualTo(0.20);
        assertThat(result.incidentCount()).isEqualTo(0L);
        assertThat(result.coOccurCount()).isEqualTo(0L);
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.explanation()).contains("zero historical incidents");
    }

    @Test
    @DisplayName("Requirement 2: One incident, zero co-occurrence -> (0 + 1.0) / (1 + 5.0) = 1/6 = 0.17")
    void testOneIncidentZeroCoOccurrence() {
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(1L, 0L);

        assertThat(result.finalWeight()).isEqualTo(0.17);
        assertThat(result.rawWeight()).isEqualTo(0.17);
        assertThat(result.incidentCount()).isEqualTo(1L);
        assertThat(result.coOccurCount()).isEqualTo(0L);
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.explanation()).contains("fallback: false");
    }

    @Test
    @DisplayName("Requirement 3: One incident, one co-occurrence -> (1 + 1.0) / (1 + 5.0) = 2/6 = 0.33")
    void testOneIncidentOneCoOccurrence() {
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(1L, 1L);

        assertThat(result.finalWeight()).isEqualTo(0.33);
        assertThat(result.rawWeight()).isEqualTo(0.33);
        assertThat(result.incidentCount()).isEqualTo(1L);
        assertThat(result.coOccurCount()).isEqualTo(1L);
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("Requirement 4: Multiple incidents calculation (N=4, co-occur=2) -> (2 + 1.0) / (4 + 5.0) = 3/9 = 0.33")
    void testMultipleIncidents() {
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(4L, 2L);

        assertThat(result.finalWeight()).isEqualTo(0.33);
        assertThat(result.rawWeight()).isEqualTo(0.33);
        assertThat(result.incidentCount()).isEqualTo(4L);
        assertThat(result.coOccurCount()).isEqualTo(2L);
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    @DisplayName("Requirement 5: Smoothing behavior pulls empirical frequency toward prior (N=10, co-occur=10, raw=0.73, smoothed=0.40 clamped)")
    void testSmoothingBehavior() {
        // Raw empirical rate is 10/10 = 1.0
        // Smoothed rate: (10 + 1.0) / (10 + 5.0) = 11/15 = 0.7333 -> clamped to 0.40
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(10L, 10L);

        assertThat(result.rawWeight()).isEqualTo(0.73);
        assertThat(result.finalWeight()).isEqualTo(0.40);
        assertThat(result.explanation()).contains("raw: 0.73");
    }

    @Test
    @DisplayName("Requirement 6: Lower clamp bound (0.05) is enforced when raw weight drops below 0.05")
    void testLowerClampEnforced() {
        // N=25, co-occur=0: (0 + 1.0) / (25 + 5.0) = 1/30 = 0.0333
        // Clamped floor: 0.05
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(25L, 0L);

        assertThat(result.rawWeight()).isEqualTo(0.03);
        assertThat(result.finalWeight()).isEqualTo(0.05);
        assertThat(result.explanation()).contains("Empirical edge weight: 0.05 (raw: 0.03");
    }

    @Test
    @DisplayName("Requirement 7: Upper clamp bound (0.40) is enforced when raw weight exceeds 0.40")
    void testUpperClampEnforced() {
        // N=5, co-occur=4: (4 + 1.0) / (5 + 5.0) = 5/10 = 0.50
        // Clamped ceiling: 0.40
        EdgeWeightResult result = EmpiricalEdgeWeightCalculator.calculate(5L, 4L);

        assertThat(result.rawWeight()).isEqualTo(0.50);
        assertThat(result.finalWeight()).isEqualTo(0.40);
        assertThat(result.explanation()).contains("Empirical edge weight: 0.40 (raw: 0.50");
    }

    @Test
    @DisplayName("Requirement 8: Exact boundary values verify clamping transitions")
    void testExactBoundaryValues() {
        // Boundary check at exactly 0.20: N=10, co-occur=2: (2 + 1.0) / (10 + 5.0) = 3/15 = 0.20
        EdgeWeightResult midResult = EmpiricalEdgeWeightCalculator.calculate(10L, 2L);
        assertThat(midResult.rawWeight()).isEqualTo(0.20);
        assertThat(midResult.finalWeight()).isEqualTo(0.20);

        // Boundary check near lower bound: N=15, co-occur=0: (0 + 1.0) / (15 + 5.0) = 1/20 = 0.05
        EdgeWeightResult minBoundary = EmpiricalEdgeWeightCalculator.calculate(15L, 0L);
        assertThat(minBoundary.rawWeight()).isEqualTo(0.05);
        assertThat(minBoundary.finalWeight()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("Requirement 9: Deterministic repeated calculation yields identical results across 100 iterations")
    void testDeterministicRepeatedCalculation() {
        EdgeWeightResult baseline = EmpiricalEdgeWeightCalculator.calculate(17L, 6L);

        for (int i = 0; i < 100; i++) {
            EdgeWeightResult iter = EmpiricalEdgeWeightCalculator.calculate(17L, 6L);
            assertThat(iter.finalWeight()).isEqualTo(baseline.finalWeight());
            assertThat(iter.rawWeight()).isEqualTo(baseline.rawWeight());
            assertThat(iter.explanation()).isEqualTo(baseline.explanation());
            assertThat(iter.fallbackUsed()).isEqualTo(baseline.fallbackUsed());
        }
    }

    @Test
    @DisplayName("Requirement 10: No negative result under any input conditions")
    void testNoNegativeResult() {
        EdgeWeightResult negIncident = EmpiricalEdgeWeightCalculator.calculate(-5L, 0L);
        assertThat(negIncident.finalWeight()).isGreaterThanOrEqualTo(0.05);

        EdgeWeightResult negCoOccur = EmpiricalEdgeWeightCalculator.calculate(10L, -3L);
        assertThat(negCoOccur.finalWeight()).isGreaterThanOrEqualTo(0.05);
    }

    @Test
    @DisplayName("Requirement 11: No result exceeds policy maximum clamp of 0.40")
    void testNoResultExceedsMaximumClamp() {
        EdgeWeightResult extreme = EmpiricalEdgeWeightCalculator.calculate(1000L, 1000L);
        assertThat(extreme.finalWeight()).isLessThanOrEqualTo(0.40);

        EdgeWeightResult smallExtreme = EmpiricalEdgeWeightCalculator.calculate(2L, 2L);
        assertThat(smallExtreme.finalWeight()).isLessThanOrEqualTo(0.40);
    }

    @Test
    @DisplayName("Requirement 12: Non-finite and invalid inputs trigger defensive fallback to prior 0.20")
    void testNonFiniteAndInvalidInputsFallback() {
        // NaN smoothing beta
        EdgeWeightResult nanBeta = EmpiricalEdgeWeightCalculator.calculate(
                10L, 5L, 0.20, Double.NaN, 0.05, 0.40);
        assertThat(nanBeta.finalWeight()).isEqualTo(0.20);
        assertThat(nanBeta.fallbackUsed()).isTrue();

        // Infinite smoothing beta
        EdgeWeightResult infBeta = EmpiricalEdgeWeightCalculator.calculate(
                10L, 5L, 0.20, Double.POSITIVE_INFINITY, 0.05, 0.40);
        assertThat(infBeta.finalWeight()).isEqualTo(0.20);
        assertThat(infBeta.fallbackUsed()).isTrue();

        // Zero or negative smoothing beta
        EdgeWeightResult zeroBeta = EmpiricalEdgeWeightCalculator.calculate(
                10L, 5L, 0.20, 0.0, 0.05, 0.40);
        assertThat(zeroBeta.finalWeight()).isEqualTo(0.20);
        assertThat(zeroBeta.fallbackUsed()).isTrue();

        // Co-occur greater than incident count is clamped defensively
        EdgeWeightResult inflatedCoOccur = EmpiricalEdgeWeightCalculator.calculate(5L, 100L);
        assertThat(inflatedCoOccur.coOccurCount()).isEqualTo(5L);
        assertThat(inflatedCoOccur.finalWeight()).isLessThanOrEqualTo(0.40);
    }
}
