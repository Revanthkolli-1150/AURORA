package com.aurora.platform.policies.dto;

import com.aurora.platform.resources.entity.ResourceType;

import java.time.Instant;
import java.util.UUID;

public record PolicyResponse(
        UUID id,
        String name,
        ResourceType targetResourceType,
        String metricName,
        Double threshold,
        String action,
        Boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {}
