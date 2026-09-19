package com.aurora.platform.incidents.dto;

import com.aurora.platform.incidents.entity.IncidentSeverity;
import com.aurora.platform.incidents.entity.IncidentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateIncidentRequest(
        @NotNull(message = "Resource ID is required")
        UUID resourceId,

        @NotBlank(message = "Title is required")
        @Size(max = 255, message = "Title must not exceed 255 characters")
        String title,

        String description,

        @NotNull(message = "Severity is required")
        IncidentSeverity severity,

        @NotNull(message = "Status is required")
        IncidentStatus status,

        @NotNull(message = "Confidence score is required")
        Double confidence,

        String rootCause,

        Instant detectedAt
) {}
