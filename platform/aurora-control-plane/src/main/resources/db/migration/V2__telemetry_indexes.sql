-- ==============================================================================
-- AURORA Control Plane Schema Migration V2
-- Add indexes for telemetry queries:
-- 1. (resource_id, timestamp ASC) for chronological resource telemetry retrieval
-- 2. (resource_id, metric_name, timestamp ASC) for filtered metric queries
-- Target: PostgreSQL 14+ (Compatible with H2 in PostgreSQL mode)
-- ==============================================================================

CREATE INDEX IF NOT EXISTS idx_telemetry_resource_timestamp_asc ON telemetry_events (resource_id, timestamp ASC);
CREATE INDEX IF NOT EXISTS idx_telemetry_resource_metric_timestamp ON telemetry_events (resource_id, metric_name, timestamp ASC);
