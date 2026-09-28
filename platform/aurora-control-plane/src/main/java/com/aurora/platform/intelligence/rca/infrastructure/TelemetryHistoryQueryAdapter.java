package com.aurora.platform.intelligence.rca.infrastructure;

import com.aurora.platform.intelligence.rca.application.TelemetryHistoryQuery;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class TelemetryHistoryQueryAdapter implements TelemetryHistoryQuery {

    private final TelemetryEventRepository telemetryEventRepository;

    public TelemetryHistoryQueryAdapter(TelemetryEventRepository telemetryEventRepository) {
        this.telemetryEventRepository = telemetryEventRepository;
    }

    @Override
    public List<TelemetryEventEntity> findTelemetryInWindow(UUID resourceId, Instant start, Instant end) {
        return telemetryEventRepository.findByResourceIdAndTimestampBetweenOrderByTimestampAsc(resourceId, start, end);
    }
}
