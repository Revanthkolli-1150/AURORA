package com.aurora.platform.dependency.dto;

import com.aurora.platform.dependency.entity.DependencyType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request payload for creating a directed resource dependency.
 * The sourceResourceId is provided via the URL path.
 */
public record CreateResourceDependencyRequest(
        @NotNull(message = "Target resource ID is required")
        UUID targetResourceId,

        @NotNull(message = "Dependency type is required")
        DependencyType dependencyType
) {}
