package com.aurora.platform.intelligence.graph.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Phase 2D-A Empirical Graph Intelligence.
 */
@Configuration
@ConfigurationProperties(prefix = "aurora.intelligence.rca.graph")
@Getter
@Setter
public class GraphIntelligenceProperties {

    /**
     * Smoothing pseudo-count (beta) balancing prior weight vs observed co-occurrences.
     * Default: 5.0.
     */
    private double smoothingBeta = 5.0;

    /**
     * Maximum historical incidents evaluated per resource (K_MAX).
     * Default: 100.
     */
    private int maxHistoryIncidents = 100;

    /**
     * Lookback window in minutes for qualifying anomalous evidence during historical incidents.
     * Default: 10 minutes.
     */
    private long lookbackMinutes = 10L;

    /**
     * Master toggle for empirical edge weighting. If false, returns static prior weight (0.20).
     * Default: true.
     */
    private boolean enabled = true;
}
