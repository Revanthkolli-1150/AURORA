package com.aurora.platform.intelligence.rca.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the Root Cause Analysis (RCA) Evidence Engine.
 */
@Configuration
@ConfigurationProperties(prefix = "aurora.intelligence.rca")
@Getter
@Setter
public class RcaProperties {

    /**
     * Investigation lookback window in minutes.
     * Evidence (anomalies, telemetry) within [detectedAt - lookbackMinutes, detectedAt] is inspected.
     * Default: 10 minutes.
     */
    private long lookbackMinutes = 10L;
}
