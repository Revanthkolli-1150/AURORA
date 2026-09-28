package com.aurora.platform.telemetry.repository;

import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TelemetryEventRepository extends JpaRepository<TelemetryEventEntity, UUID> {

    List<TelemetryEventEntity> findByResourceIdOrderByTimestampAsc(UUID resourceId);

    List<TelemetryEventEntity> findByResourceIdAndMetricNameOrderByTimestampAsc(UUID resourceId, String metricName);

    List<TelemetryEventEntity> findByResourceIdAndTimestampBetweenOrderByTimestampAsc(UUID resourceId, java.time.Instant start, java.time.Instant end);

    List<TelemetryEventEntity> findByResourceIdOrderByTimestampDesc(UUID resourceId);

    List<TelemetryEventEntity> findByResourceIdAndMetricNameOrderByTimestampDesc(UUID resourceId, String metricName);
}
