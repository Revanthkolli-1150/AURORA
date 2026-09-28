package com.aurora.platform.intelligence.historical.domain.service;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.historical.domain.model.SimilarityScore;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentSimilarityCalculatorTest {

    private IncidentSimilarityCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new IncidentSimilarityCalculator();
    }

    @Test
    @DisplayName("1. Identical signatures produce perfect 1.00 similarity score")
    void testIdenticalSignatures() {
        UUID resId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                UUID.randomUUID(), resId, "pg-db-01", ResourceType.DATABASE,
                IncidentSeverity.CRITICAL,
                Set.of("db_connections", "cpu_usage"),
                Set.of("DOWNSTREAM:SERVICE", "UPSTREAM:SERVER"),
                "Connection pool exhausted",
                Instant.now().minusSeconds(300), null
        );

        IncidentSignature candidate = new IncidentSignature(
                UUID.randomUUID(), resId, "pg-db-01", ResourceType.DATABASE,
                IncidentSeverity.CRITICAL,
                Set.of("db_connections", "cpu_usage"),
                Set.of("DOWNSTREAM:SERVICE", "UPSTREAM:SERVER"),
                "Previous pool exhaustion",
                Instant.now().minusSeconds(1000), Instant.now().minusSeconds(700)
        );

        SimilarityScore score = calculator.calculate(target, candidate);
        assertThat(score.compositeScore()).isEqualTo(1.00);
        assertThat(score.metricScore()).isEqualTo(1.00);
        assertThat(score.topologyScore()).isEqualTo(1.00);
        assertThat(score.resourceScore()).isEqualTo(1.00);
        assertThat(score.severityScore()).isEqualTo(1.00);
        assertThat(score.matchedMetrics()).containsExactly("cpu_usage", "db_connections");
        assertThat(score.matchedTopologyTokens()).containsExactly("DOWNSTREAM:SERVICE", "UPSTREAM:SERVER");
        assertThat(score.explanation()).contains("Matched 100.0%");
    }

    @Test
    @DisplayName("2. Completely dissimilar signatures produce 0.00 similarity score")
    void testDisjointSignatures() {
        IncidentSignature target = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "api-gw", ResourceType.SERVICE,
                IncidentSeverity.CRITICAL,
                Set.of("http_5xx_rate"),
                Set.of("UPSTREAM:DATABASE"),
                null, Instant.now(), null
        );

        IncidentSignature candidate = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "worker-node", ResourceType.SERVER,
                IncidentSeverity.LOW,
                Set.of("disk_io"),
                Set.of("DOWNSTREAM:APPLICATION"),
                null, Instant.now().minusSeconds(500), Instant.now().minusSeconds(200)
        );

        SimilarityScore score = calculator.calculate(target, candidate);
        assertThat(score.compositeScore()).isEqualTo(0.00);
        assertThat(score.metricScore()).isEqualTo(0.00);
        assertThat(score.topologyScore()).isEqualTo(0.00);
        assertThat(score.resourceScore()).isEqualTo(0.00);
        assertThat(score.severityScore()).isEqualTo(0.00);
        assertThat(score.matchedMetrics()).isEmpty();
        assertThat(score.matchedTopologyTokens()).isEmpty();
    }

    @Test
    @DisplayName("3. Metric Jaccard: intersection over union")
    void testMetricJaccard() {
        // |A ∩ B| = 1, |A ∪ B| = 3 -> Jaccard = 1/3 = 0.3333
        double jaccard = calculator.computeJaccard(
                Set.of("cpu_usage", "memory_usage"),
                Set.of("cpu_usage", "disk_utilization")
        );
        assertThat(jaccard).isEqualTo(0.3333);
    }

    @Test
    @DisplayName("4. Topology Jaccard: intersection over union of canonical tokens")
    void testTopologyJaccard() {
        Set<String> topoA = Set.of("UPSTREAM:DATABASE", "DOWNSTREAM:APPLICATION");
        Set<String> topoB = Set.of("UPSTREAM:DATABASE");

        // Intersection: 1, Union: 2 -> Jaccard = 0.50
        assertThat(calculator.computeJaccard(topoA, topoB)).isEqualTo(0.50);
    }

    @Test
    @DisplayName("5. Resource type match: exact match = 1.0, mismatch = 0.0")
    void testResourceScore() {
        assertThat(calculator.computeResourceScore(ResourceType.DATABASE, ResourceType.DATABASE)).isEqualTo(1.0);
        assertThat(calculator.computeResourceScore(ResourceType.SERVICE, ResourceType.DATABASE)).isEqualTo(0.0);
        assertThat(calculator.computeResourceScore(null, ResourceType.DATABASE)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("6, 7, 8. Severity score: exact = 1.0, adjacent = 0.5, distant = 0.0")
    void testSeverityScore() {
        // 6. Same severity: 1.0
        assertThat(calculator.computeSeverityScore(IncidentSeverity.CRITICAL, IncidentSeverity.CRITICAL)).isEqualTo(1.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.HIGH, IncidentSeverity.HIGH)).isEqualTo(1.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.MEDIUM, IncidentSeverity.MEDIUM)).isEqualTo(1.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.LOW, IncidentSeverity.LOW)).isEqualTo(1.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.INFO, IncidentSeverity.INFO)).isEqualTo(1.0);

        // 7. One severity level apart: 0.5
        assertThat(calculator.computeSeverityScore(IncidentSeverity.CRITICAL, IncidentSeverity.HIGH)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.HIGH, IncidentSeverity.CRITICAL)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.HIGH, IncidentSeverity.MEDIUM)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.MEDIUM, IncidentSeverity.HIGH)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.MEDIUM, IncidentSeverity.LOW)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.LOW, IncidentSeverity.MEDIUM)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.LOW, IncidentSeverity.INFO)).isEqualTo(0.5);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.INFO, IncidentSeverity.LOW)).isEqualTo(0.5);

        // 8. Two or more severity levels apart: 0.0
        assertThat(calculator.computeSeverityScore(IncidentSeverity.CRITICAL, IncidentSeverity.MEDIUM)).isEqualTo(0.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.CRITICAL, IncidentSeverity.LOW)).isEqualTo(0.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.CRITICAL, IncidentSeverity.INFO)).isEqualTo(0.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.HIGH, IncidentSeverity.LOW)).isEqualTo(0.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.HIGH, IncidentSeverity.INFO)).isEqualTo(0.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.MEDIUM, IncidentSeverity.INFO)).isEqualTo(0.0);

        // Null safety
        assertThat(calculator.computeSeverityScore(null, IncidentSeverity.CRITICAL)).isEqualTo(0.0);
        assertThat(calculator.computeSeverityScore(IncidentSeverity.CRITICAL, null)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Explicit stable severity ranking independent of enum ordinal")
    void testExplicitSeverityRanking() {
        // Assert exact mapping contract
        assertThat(IncidentSimilarityCalculator.getSeverityRank(IncidentSeverity.INFO)).isEqualTo(0);
        assertThat(IncidentSimilarityCalculator.getSeverityRank(IncidentSeverity.LOW)).isEqualTo(1);
        assertThat(IncidentSimilarityCalculator.getSeverityRank(IncidentSeverity.MEDIUM)).isEqualTo(2);
        assertThat(IncidentSimilarityCalculator.getSeverityRank(IncidentSeverity.HIGH)).isEqualTo(3);
        assertThat(IncidentSimilarityCalculator.getSeverityRank(IncidentSeverity.CRITICAL)).isEqualTo(4);

        // Assert all existing severity constants are explicitly covered
        for (IncidentSeverity s : IncidentSeverity.values()) {
            assertThat(IncidentSimilarityCalculator.getSeverityRank(s)).isBetween(0, 4);
        }

        // Complete 5x5 pair-wise distance matrix verification
        for (IncidentSeverity s1 : IncidentSeverity.values()) {
            for (IncidentSeverity s2 : IncidentSeverity.values()) {
                int rankDiff = Math.abs(IncidentSimilarityCalculator.getSeverityRank(s1)
                        - IncidentSimilarityCalculator.getSeverityRank(s2));
                double expectedScore = (rankDiff == 0) ? 1.0 : (rankDiff == 1) ? 0.5 : 0.0;
                assertThat(calculator.computeSeverityScore(s1, s2))
                        .as("Severity score between %s and %s", s1, s2)
                        .isEqualTo(expectedScore);
            }
        }
    }

    @Test
    @DisplayName("9, 10. Metric empty sets semantics: both empty = 1.0, one empty = 0.0")
    void testMetricEmptySetSemantics() {
        // 9. Both empty: 1.0
        assertThat(calculator.computeJaccard(Set.of(), Set.of())).isEqualTo(1.0);

        // 10. Exactly one empty: 0.0
        assertThat(calculator.computeJaccard(Set.of("cpu"), Set.of())).isEqualTo(0.0);
        assertThat(calculator.computeJaccard(Set.of(), Set.of("cpu"))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("11, 12. Topology empty sets semantics: both empty = 1.0, one empty = 0.0")
    void testTopologyEmptySetSemantics() {
        // 11. Both empty: 1.0
        assertThat(calculator.computeJaccard(Set.of(), Set.of())).isEqualTo(1.0);

        // 12. Exactly one empty: 0.0
        assertThat(calculator.computeJaccard(Set.of("UPSTREAM:DB"), Set.of())).isEqualTo(0.0);
        assertThat(calculator.computeJaccard(Set.of(), Set.of("UPSTREAM:DB"))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("13. Score bounds: composite and component scores strictly in [0.0, 1.0]")
    void testScoreBounds() {
        for (IncidentSeverity s1 : IncidentSeverity.values()) {
            for (IncidentSeverity s2 : IncidentSeverity.values()) {
                double sev = calculator.computeSeverityScore(s1, s2);
                assertThat(sev).isBetween(0.0, 1.0);
            }
        }

        for (ResourceType r1 : ResourceType.values()) {
            for (ResourceType r2 : ResourceType.values()) {
                double res = calculator.computeResourceScore(r1, r2);
                assertThat(res).isBetween(0.0, 1.0);
            }
        }
    }

    @Test
    @DisplayName("14. Composite formula matches exact weights (0.40, 0.30, 0.20, 0.10)")
    void testCompositeWeights() {
        // Target and candidate:
        // - metric: 1/2 = 0.50 -> * 0.40 = 0.20
        // - topology: 1/1 = 1.00 -> * 0.30 = 0.30
        // - resource: mismatch = 0.00 -> * 0.20 = 0.00
        // - severity: adjacent = 0.50 -> * 0.10 = 0.05
        // Expected: 0.20 + 0.30 + 0.00 + 0.05 = 0.55
        IncidentSignature target = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "resA", ResourceType.SERVICE,
                IncidentSeverity.CRITICAL,
                Set.of("metric_1", "metric_2"),
                Set.of("UPSTREAM:DATABASE"),
                null, Instant.now(), null
        );

        IncidentSignature candidate = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "resB", ResourceType.APPLICATION,
                IncidentSeverity.HIGH,
                Set.of("metric_1"),
                Set.of("UPSTREAM:DATABASE"),
                "Memory leak", Instant.now().minusSeconds(100), Instant.now()
        );

        SimilarityScore score = calculator.calculate(target, candidate);
        assertThat(score.compositeScore()).isEqualTo(0.55);
        assertThat(score.metricScore()).isEqualTo(0.50);
        assertThat(score.topologyScore()).isEqualTo(1.00);
        assertThat(score.resourceScore()).isEqualTo(0.00);
        assertThat(score.severityScore()).isEqualTo(0.50);
        assertThat(score.explanation()).contains("Memory leak");
    }

    @Test
    @DisplayName("15, 16. Deterministic repeated execution and identical explanation")
    void testDeterministicRepeatedExecution() {
        IncidentSignature target = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "api-srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH,
                Set.of("metric_b", "metric_a"),
                Set.of("UPSTREAM:DATABASE", "DOWNSTREAM:GATEWAY"),
                null, Instant.now(), null
        );

        IncidentSignature candidate = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "api-srv-old", ResourceType.SERVICE,
                IncidentSeverity.MEDIUM,
                Set.of("metric_a", "metric_c"),
                Set.of("UPSTREAM:DATABASE"),
                "Upstream timeout", Instant.now().minusSeconds(500), Instant.now().minusSeconds(300)
        );

        SimilarityScore firstRun = calculator.calculate(target, candidate);

        for (int i = 0; i < 100; i++) {
            SimilarityScore run = calculator.calculate(target, candidate);
            assertThat(run.compositeScore()).isEqualTo(firstRun.compositeScore());
            assertThat(run.metricScore()).isEqualTo(firstRun.metricScore());
            assertThat(run.topologyScore()).isEqualTo(firstRun.topologyScore());
            assertThat(run.resourceScore()).isEqualTo(firstRun.resourceScore());
            assertThat(run.severityScore()).isEqualTo(firstRun.severityScore());
            assertThat(run.matchedMetrics()).isEqualTo(firstRun.matchedMetrics());
            assertThat(run.matchedTopologyTokens()).isEqualTo(firstRun.matchedTopologyTokens());
            assertThat(run.explanation()).isEqualTo(firstRun.explanation());
        }
    }

    @Test
    @DisplayName("Null safety: null target or candidate throws NullPointerException")
    void testNullValidation() {
        IncidentSignature valid = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "res", ResourceType.DATABASE,
                IncidentSeverity.LOW, Set.of(), Set.of(), null, Instant.now(), null
        );

        assertThatThrownBy(() -> calculator.calculate(null, valid))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calculator.calculate(valid, null))
                .isInstanceOf(NullPointerException.class);
    }
}
