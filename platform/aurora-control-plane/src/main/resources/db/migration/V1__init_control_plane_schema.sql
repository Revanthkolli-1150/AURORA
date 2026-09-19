-- ==============================================================================
-- AURORA Control Plane Schema Migration V1
-- Target: PostgreSQL 14+ (Standard SQL compatible with H2 in PostgreSQL mode)
-- ==============================================================================

-- 1. Resources: Monitored components/services/infrastructure
CREATE TABLE resources (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    environment VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    metadata TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_resources_name ON resources (name);
CREATE INDEX idx_resources_type ON resources (type);
CREATE INDEX idx_resources_status ON resources (status);
CREATE INDEX idx_resources_environment ON resources (environment);

-- 2. Telemetry Events: Metric/Log/Trace/Event observations
CREATE TABLE telemetry_events (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL,
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    type VARCHAR(50) NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    "value" DOUBLE PRECISION NOT NULL,
    unit VARCHAR(50) NOT NULL,
    metadata TEXT,
    CONSTRAINT fk_telemetry_resource FOREIGN KEY (resource_id) REFERENCES resources (id) ON DELETE CASCADE
);

CREATE INDEX idx_telemetry_resource_timestamp ON telemetry_events (resource_id, timestamp DESC);
CREATE INDEX idx_telemetry_metric_name ON telemetry_events (metric_name);

-- 3. Incidents: Correlated reliability problems
CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    resource_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    severity VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    root_cause TEXT,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_incidents_resource FOREIGN KEY (resource_id) REFERENCES resources (id) ON DELETE CASCADE
);

CREATE INDEX idx_incidents_resource_id ON incidents (resource_id);
CREATE INDEX idx_incidents_status ON incidents (status);
CREATE INDEX idx_incidents_severity ON incidents (severity);
CREATE INDEX idx_incidents_detected_at ON incidents (detected_at DESC);

-- 4. Recovery Plans: Remediation strategy for correlated incidents
CREATE TABLE recovery_plans (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    reasoning TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    risk VARCHAR(50) NOT NULL,
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_recovery_plans_incident FOREIGN KEY (incident_id) REFERENCES incidents (id) ON DELETE CASCADE
);

CREATE INDEX idx_recovery_plans_incident_id ON recovery_plans (incident_id);

-- 5. Recovery Actions: Discrete execution steps in a recovery plan
CREATE TABLE recovery_actions (
    id UUID PRIMARY KEY,
    recovery_plan_id UUID NOT NULL,
    action_type VARCHAR(100) NOT NULL,
    target VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    result TEXT,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_recovery_actions_plan FOREIGN KEY (recovery_plan_id) REFERENCES recovery_plans (id) ON DELETE CASCADE
);

CREATE INDEX idx_recovery_actions_plan_id ON recovery_actions (recovery_plan_id);

-- 6. Policies: Reliability constraints and operational thresholds
CREATE TABLE policies (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    target_resource_type VARCHAR(50) NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    threshold DOUBLE PRECISION NOT NULL,
    action VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_policies_target_resource_type ON policies (target_resource_type);
