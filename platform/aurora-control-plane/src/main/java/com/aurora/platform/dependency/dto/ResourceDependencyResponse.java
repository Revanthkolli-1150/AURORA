package com.aurora.platform.dependency.dto;

import com.aurora.platform.dependency.entity.DependencyType;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload representing a directed dependency relationship between resources.
 * Semantics: sourceResourceId DEPENDS_ON targetResourceId
 */
public record ResourceDependencyResponse(
        UUID id,
        UUID sourceResourceId,
        UUID targetResourceId,
        DependencyType dependencyType,
        Instant createdAt
) {}
