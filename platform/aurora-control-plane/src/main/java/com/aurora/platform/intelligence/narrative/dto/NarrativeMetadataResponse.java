package com.aurora.platform.intelligence.narrative.dto;

import java.time.Instant;

/**
 * Metadata record describing provenance and operational performance of a narrative generation.
 */
public record NarrativeMetadataResponse(
        String provider,
        String modelName,
        String promptVersion,
        Boolean fallbackUsed,
        Integer generationDurationMs,
        Instant createdAt
) {
}
