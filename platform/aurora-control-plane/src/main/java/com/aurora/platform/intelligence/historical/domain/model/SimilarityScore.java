package com.aurora.platform.intelligence.historical.domain.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Encapsulates the deterministic similarity computation results between two incident signatures,
 * including individual sub-factor scores and explainability metadata.
 */
public record SimilarityScore(
        double compositeScore,
        double metricScore,
        double topologyScore,
        double resourceScore,
        double severityScore,
        Set<String> matchedMetrics,
        Set<String> matchedTopologyTokens,
        String explanation
) {
    public SimilarityScore {
        Objects.requireNonNull(matchedMetrics, "matchedMetrics must not be null");
        Objects.requireNonNull(matchedTopologyTokens, "matchedTopologyTokens must not be null");
        Objects.requireNonNull(explanation, "explanation must not be null");
        matchedMetrics = Collections.unmodifiableSet(matchedMetrics);
        matchedTopologyTokens = Collections.unmodifiableSet(matchedTopologyTokens);
    }
}
