package com.aurora.platform.incident.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aurora.intelligence.incident")
@Getter
@Setter
public class IncidentCorrelationProperties {

    /**
     * Temporal correlation window in seconds.
     * Subsequent anomalies on the same resource within this window attach as evidence to an active incident.
     */
    private long correlationWindowSeconds = 300L;
}
