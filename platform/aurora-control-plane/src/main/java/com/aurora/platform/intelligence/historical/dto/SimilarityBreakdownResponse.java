package com.aurora.platform.intelligence.historical.dto;

import java.util.Set;

/**
 * Detailed breakdown of individual similarity sub-factors.
 */
public record SimilarityBreakdownResponse(
        Double metricScore,
        Double topologyScore,
        Double resourceScore,
        Double severityScore,
        Set<String> matchedMetrics,
        Set<String> matchedTopologyTokens
) {}
