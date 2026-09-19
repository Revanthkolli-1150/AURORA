package com.aurora.platform.telemetry.service;

import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;

import java.util.List;
import java.util.UUID;

public interface TelemetryService {

    TelemetryEventResponse ingestTelemetry(IngestTelemetryRequest request);

    List<TelemetryEventResponse> getTelemetryByResourceId(UUID resourceId);
}
