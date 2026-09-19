package com.aurora.platform.resources.dto;

import com.aurora.platform.resources.entity.ResourceStatus;
import com.aurora.platform.resources.entity.ResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateResourceRequest(
        @NotBlank(message = "Resource name is required")
        @Size(max = 255, message = "Resource name must not exceed 255 characters")
        String name,

        @NotNull(message = "Resource type is required")
        ResourceType type,

        @NotNull(message = "Resource status is required")
        ResourceStatus status,

        @NotBlank(message = "Environment is required")
        @Size(max = 50, message = "Environment must not exceed 50 characters")
        String environment,

        @NotBlank(message = "Host is required")
        @Size(max = 255, message = "Host must not exceed 255 characters")
        String host,

        Map<String, Object> metadata
) {}
