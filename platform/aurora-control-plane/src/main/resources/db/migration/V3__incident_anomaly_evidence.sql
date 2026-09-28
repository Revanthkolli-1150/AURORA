-- V3__incident_anomaly_evidence.sql
-- Phase 1D: Incident Intelligence & Anomaly Correlation

-- 1. Make incidents.confidence nullable as confidence belongs to future Root Cause Analysis, not raw anomaly evidence
ALTER TABLE incidents ALTER COLUMN confidence DROP NOT NULL;

-- 2. Create incident_anomaly_evidence table to preserve evidence supporting an incident
CREATE TABLE incident_anomaly_evidence (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    resource_id UUID NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    observed_value DOUBLE PRECISION NOT NULL,
    anomaly_score DOUBLE PRECISION NOT NULL,
    z_score DOUBLE PRECISION,
    detection_method VARCHAR(100) NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_incident_evidence_incident FOREIGN KEY (incident_id) REFERENCES incidents (id) ON DELETE CASCADE,
    CONSTRAINT fk_incident_evidence_resource FOREIGN KEY (resource_id) REFERENCES resources (id) ON DELETE CASCADE
);

-- 3. Composite indexes for high-throughput deterministic correlation and evidence retrieval
CREATE INDEX idx_incidents_resource_status ON incidents (resource_id, status);
CREATE INDEX idx_incidents_resource_detected ON incidents (resource_id, detected_at DESC);
CREATE INDEX idx_incident_evidence_incident_observed ON incident_anomaly_evidence (incident_id, observed_at ASC);
CREATE INDEX idx_incident_evidence_resource ON incident_anomaly_evidence (resource_id);
