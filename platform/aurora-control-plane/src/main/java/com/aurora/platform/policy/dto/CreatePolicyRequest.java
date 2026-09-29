package com.aurora.platform.policy.dto;

import com.aurora.platform.resource.entity.ResourceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePolicyRequest(
        @NotBlank(message = "Policy name is required")
        @Size(max = 255, message = "Policy name must not exceed 255 characters")
        String name,

        @NotNull(message = "Target resource type is required")
        ResourceType targetResourceType,

        @NotBlank(message = "Metric name is required")
        @Size(max = 255, message = "Metric name must not exceed 255 characters")
        String metricName,

        @NotNull(message = "Threshold is required")
        Double threshold,

        @NotBlank(message = "Action is required")
        @Size(max = 100, message = "Action must not exceed 100 characters")
        String action,

        Boolean enabled
) {}
