package com.aurora.platform.intelligence.rca.application;

import com.aurora.platform.telemetry.entity.TelemetryEventEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application query port for accessing historical telemetry events required by RCA.
 */
public interface TelemetryHistoryQuery {

    List<TelemetryEventEntity> findTelemetryInWindow(UUID resourceId, Instant start, Instant end);
}
