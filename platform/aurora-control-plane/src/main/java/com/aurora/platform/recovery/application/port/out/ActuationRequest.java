package com.aurora.platform.recovery.application.port.out;

import com.aurora.platform.resource.entity.ResourceType;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record ActuationRequest(
        UUID actionId,
        UUID attemptId,
        String idempotencyKey,
        UUID targetResourceId,
        String targetEnvironment,
        ResourceType targetResourceType,
        String targetResourceName,
        String actionType,
        Map<String, String> parameters,
        Duration timeout
) {
    public ActuationRequest {
        if (parameters == null) {
            parameters = Collections.emptyMap();
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
    }
}
