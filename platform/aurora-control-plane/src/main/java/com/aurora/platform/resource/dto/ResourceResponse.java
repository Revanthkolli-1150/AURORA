package com.aurora.platform.resource.dto;

import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ResourceResponse(
        UUID id,
        String name,
        ResourceType type,
        ResourceStatus status,
        String environment,
        String host,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {}
