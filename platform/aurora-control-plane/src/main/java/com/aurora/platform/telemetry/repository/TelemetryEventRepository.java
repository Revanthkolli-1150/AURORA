package com.aurora.platform.telemetry.repository;

import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TelemetryEventRepository extends JpaRepository<TelemetryEventEntity, UUID> {

    List<TelemetryEventEntity> findByResourceIdOrderByTimestampAsc(UUID resourceId);

    List<TelemetryEventEntity> findByResourceIdAndMetricNameOrderByTimestampAsc(UUID resourceId, String metricName);

    List<TelemetryEventEntity> findByResourceIdAndTimestampBetweenOrderByTimestampAsc(UUID resourceId, Instant start, Instant end);

    List<TelemetryEventEntity> findByResourceIdOrderByTimestampDesc(UUID resourceId);

    List<TelemetryEventEntity> findByResourceIdAndMetricNameOrderByTimestampDesc(UUID resourceId, String metricName);

    List<TelemetryEventEntity> findByResourceIdAndMetricNameAndTimestampGreaterThanEqualOrderByTimestampAsc(UUID resourceId, String metricName, Instant since);

    List<TelemetryEventEntity> findByResourceIdAndMetricNameAndTimestampBetweenOrderByTimestampAsc(UUID resourceId, String metricName, Instant start, Instant end);
}
