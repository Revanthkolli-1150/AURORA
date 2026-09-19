package com.aurora.platform.infrastructure.health;

import com.aurora.platform.infrastructure.configuration.AuroraProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Component
public class ControlPlaneHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final AuroraProperties properties;

    public ControlPlaneHealthIndicator(DataSource dataSource, AuroraProperties properties) {
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            boolean valid = connection.isValid(2);
            if (valid) {
                return Health.up()
                        .withDetail("version", properties.version())
                        .withDetail("environment", properties.environment())
                        .withDetail("database", "UP")
                        .build();
            } else {
                return Health.down()
                        .withDetail("version", properties.version())
                        .withDetail("database", "Connection validation failed")
                        .build();
            }
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("version", properties.version())
                    .withDetail("database", "DOWN")
                    .build();
        }
    }
}
