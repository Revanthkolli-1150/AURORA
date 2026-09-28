package com.aurora.platform.intelligence.historical.domain.service;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.historical.domain.model.SimilarityScore;
import com.aurora.platform.resource.entity.ResourceType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure domain service for calculating deterministic, explainable similarity scores
 * between two incident signatures without external libraries, ML, or embeddings.
 */
public class IncidentSimilarityCalculator {

    public static final double WEIGHT_METRIC = 0.40;
    public static final double WEIGHT_TOPOLOGY = 0.30;
    public static final double WEIGHT_RESOURCE = 0.20;
    public static final double WEIGHT_SEVERITY = 0.10;

    /**
     * Calculates the deterministic composite similarity score between a target incident
     * and a candidate historical incident.
     *
     * @param target    The active or investigated target incident signature
     * @param candidate The historical resolved incident signature
     * @return Fully populated and bounded {@link SimilarityScore}
     */
    public SimilarityScore calculate(IncidentSignature target, IncidentSignature candidate) {
        Objects.requireNonNull(target, "target signature must not be null");
        Objects.requireNonNull(candidate, "candidate signature must not be null");

        // 1. Metric Similarity: Jaccard(M_target, M_candidate)
        Set<String> matchedMetrics = new TreeSet<>(target.anomalousMetrics());
        matchedMetrics.retainAll(candidate.anomalousMetrics());
        double metricScore = computeJaccard(target.anomalousMetrics(), candidate.anomalousMetrics());

        // 2. Topology Similarity: Jaccard(T_target, T_candidate)
        Set<String> matchedTopologyTokens = new TreeSet<>(target.topologyTokens());
        matchedTopologyTokens.retainAll(candidate.topologyTokens());
        double topologyScore = computeJaccard(target.topologyTokens(), candidate.topologyTokens());

        // 3. Resource Type Similarity: 1.0 exact match, 0.0 otherwise
        double resourceScore = computeResourceScore(target.resourceType(), candidate.resourceType());

        // 4. Severity Similarity: 1.0 exact match, 0.5 one step apart, 0.0 otherwise
        double severityScore = computeSeverityScore(target.severity(), candidate.severity());

        // 5. Composite Score S in [0.00, 1.00]
        double rawComposite = (WEIGHT_METRIC * metricScore)
                + (WEIGHT_TOPOLOGY * topologyScore)
                + (WEIGHT_RESOURCE * resourceScore)
                + (WEIGHT_SEVERITY * severityScore);

        // Bounded and rounded to 4 decimal places for deterministic representation
        double compositeScore = Math.min(1.0, Math.max(0.0, Math.round(rawComposite * 10000.0) / 10000.0));

        // 6. Generate human-auditable explanation
        String explanation = generateExplanation(
                candidate, compositeScore, metricScore, topologyScore, resourceScore, severityScore,
                matchedMetrics, matchedTopologyTokens
        );

        return new SimilarityScore(
                compositeScore,
                metricScore,
                topologyScore,
                resourceScore,
                severityScore,
                matchedMetrics,
                matchedTopologyTokens,
                explanation
        );
    }

    /**
     * Calculates standard Jaccard similarity coefficient: |A ∩ B| / |A ∪ B|.
     * If both sets are empty, returns 1.0 (structurally identical empty sets).
     * If exactly one set is empty, returns 0.0.
     */
    public double computeJaccard(Set<String> setA, Set<String> setB) {
        if (setA == null || setB == null) {
            return 0.0;
        }
        if (setA.isEmpty() && setB.isEmpty()) {
            return 1.0;
        }
        if (setA.isEmpty() || setB.isEmpty()) {
            return 0.0;
        }

        Set<String> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<String> union = new HashSet<>(setA);
        union.addAll(setB);

        double jaccard = (double) intersection.size() / (double) union.size();
        return Math.round(jaccard * 10000.0) / 10000.0;
    }

    /**
     * Calculates resource type match: 1.0 if identical type, 0.0 otherwise.
     */
    public double computeResourceScore(ResourceType typeA, ResourceType typeB) {
        if (typeA == null || typeB == null) {
            return 0.0;
        }
        return typeA == typeB ? 1.0 : 0.0;
    }

    /**
     * Calculates severity affinity score:
     * 1.0 if identical severity,
     * 0.5 if exactly one severity step apart,
     * 0.0 otherwise.
     *
     * <p>Uses explicit, stable severity ranking independent of Java enum ordinal or declaration order.
     */
    public double computeSeverityScore(IncidentSeverity sA, IncidentSeverity sB) {
        if (sA == null || sB == null) {
            return 0.0;
        }
        int rankA = getSeverityRank(sA);
        int rankB = getSeverityRank(sB);
        int diff = Math.abs(rankA - rankB);
        if (diff == 0) {
            return 1.0;
        } else if (diff == 1) {
            return 0.5;
        } else {
            return 0.0;
        }
    }

    /**
     * Explicit, stable severity ranking mapping independent of enum declaration order.
     */
    public static int getSeverityRank(IncidentSeverity severity) {
        if (severity == null) {
            throw new IllegalArgumentException("severity must not be null");
        }
        return switch (severity) {
            case INFO -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case CRITICAL -> 4;
        };
    }

    private String generateExplanation(
            IncidentSignature candidate,
            double composite,
            double metric,
            double topology,
            double resource,
            double severity,
            Set<String> matchedMetrics,
            Set<String> matchedTopology
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Matched %.1f%% with resolved incident %s: ",
                composite * 100.0, candidate.incidentId()));

        if (resource == 1.0) {
            sb.append("identical resource type (").append(candidate.resourceType()).append("), ");
        } else {
            sb.append("different resource type, ");
        }

        if (metric > 0.0) {
            sb.append(String.format(Locale.ROOT, "metric overlap %.0f%% %s, ",
                    metric * 100.0, matchedMetrics));
        } else {
            sb.append("no metric overlap, ");
        }

        if (topology > 0.0) {
            sb.append(String.format(Locale.ROOT, "topology overlap %.0f%% %s, ",
                    topology * 100.0, matchedTopology));
        } else {
            sb.append("disjoint topology, ");
        }

        if (severity == 1.0) {
            sb.append("identical severity (").append(candidate.severity()).append(").");
        } else if (severity == 0.5) {
            sb.append("adjacent severity (").append(candidate.severity()).append(").");
        } else {
            sb.append("divergent severity (").append(candidate.severity()).append(").");
        }

        if (candidate.primaryRcaCause() != null && !candidate.primaryRcaCause().isBlank()) {
            sb.append(" Historical confirmed root cause: ").append(candidate.primaryRcaCause()).append(".");
        }

        return sb.toString();
    }
}
