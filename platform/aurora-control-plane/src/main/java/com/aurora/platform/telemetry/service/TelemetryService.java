package com.aurora.platform.telemetry.service;

import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TelemetryService {

    TelemetryEventResponse ingestTelemetry(IngestTelemetryRequest request);

    List<TelemetryEventResponse> getTelemetryByResourceId(UUID resourceId, String metricName);

    default List<TelemetryEventResponse> getTelemetryByResourceId(UUID resourceId) {
        return getTelemetryByResourceId(resourceId, null);
    }

    List<TelemetryEventResponse> getTelemetrySince(UUID resourceId, String metricName, Instant since);

    List<TelemetryEventResponse> getTelemetryInWindow(UUID resourceId, String metricName, Instant start, Instant end);
}
