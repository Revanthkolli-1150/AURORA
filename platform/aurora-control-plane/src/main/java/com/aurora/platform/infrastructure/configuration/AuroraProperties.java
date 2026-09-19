package com.aurora.platform.infrastructure.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aurora.control-plane")
public record AuroraProperties(
        String version,
        String environment
) {
    public AuroraProperties {
        if (version == null) {
            version = "0.1.0";
        }
        if (environment == null) {
            environment = "development";
        }
    }
}
