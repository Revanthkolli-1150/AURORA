-- ==============================================================================
-- AURORA Control Plane Schema Migration V7
-- Phase 5A: Controlled Actuation Foundation
-- ==============================================================================

-- 1. Extend recovery_actions with canonical target identity and operator approval audit
ALTER TABLE recovery_actions ADD COLUMN target_resource_id UUID;
ALTER TABLE recovery_actions ADD COLUMN target_environment VARCHAR(50);
ALTER TABLE recovery_actions ADD COLUMN target_resource_type VARCHAR(50);
ALTER TABLE recovery_actions ADD COLUMN target_resource_name VARCHAR(255);
ALTER TABLE recovery_actions ADD COLUMN approved_by_user_id VARCHAR(100);
ALTER TABLE recovery_actions ADD COLUMN approved_by_email VARCHAR(255);
ALTER TABLE recovery_actions ADD COLUMN approved_by_capability VARCHAR(50);
ALTER TABLE recovery_actions ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE recovery_actions ADD COLUMN approval_reason TEXT;
ALTER TABLE recovery_actions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Backfill step 1: Match incident resource where target matches name or stringified UUID
UPDATE recovery_actions
SET target_resource_id = (
    SELECT r.id FROM resources r
    JOIN incidents i ON i.resource_id = r.id
    JOIN recovery_plans rp ON rp.incident_id = i.id
    WHERE rp.id = recovery_actions.recovery_plan_id
      AND (recovery_actions.target = r.name OR recovery_actions.target = CAST(r.id AS VARCHAR(255)))
    FETCH FIRST 1 ROWS ONLY
)
WHERE target_resource_id IS NULL;

-- Backfill step 2: Match resource by target name directly
UPDATE recovery_actions
SET target_resource_id = (
    SELECT r.id FROM resources r
    WHERE r.name = recovery_actions.target
    FETCH FIRST 1 ROWS ONLY
)
WHERE target_resource_id IS NULL;

-- Backfill step 3: Fall back to incident's target resource
UPDATE recovery_actions
SET target_resource_id = (
    SELECT i.resource_id FROM incidents i
    JOIN recovery_plans rp ON rp.incident_id = i.id
    WHERE rp.id = recovery_actions.recovery_plan_id
    FETCH FIRST 1 ROWS ONLY
)
WHERE target_resource_id IS NULL;

-- Backfill step 4: Populate snapshot context attributes from resolved resource
UPDATE recovery_actions
SET target_environment = (SELECT r.environment FROM resources r WHERE r.id = recovery_actions.target_resource_id),
    target_resource_type = (SELECT r.type FROM resources r WHERE r.id = recovery_actions.target_resource_id),
    target_resource_name = (SELECT r.name FROM resources r WHERE r.id = recovery_actions.target_resource_id)
WHERE target_resource_id IS NOT NULL AND target_resource_name IS NULL;

-- Enforce NOT NULL constraints on canonical target identity
-- If any row could not be resolved, this will fail the migration
ALTER TABLE recovery_actions ALTER COLUMN target_resource_id SET NOT NULL;
ALTER TABLE recovery_actions ALTER COLUMN target_environment SET NOT NULL;
ALTER TABLE recovery_actions ALTER COLUMN target_resource_type SET NOT NULL;
ALTER TABLE recovery_actions ALTER COLUMN target_resource_name SET NOT NULL;

-- Add foreign key constraint to canonical resource
ALTER TABLE recovery_actions ADD CONSTRAINT fk_recovery_actions_target_resource
    FOREIGN KEY (target_resource_id) REFERENCES resources (id);

-- Composite index for anti-flapping scoped by (target_resource_id, action_type)
CREATE INDEX idx_recovery_actions_target_res ON recovery_actions (target_resource_id, action_type);

-- 2. Execution Attempts: Concrete dispatch and execution tracking
CREATE TABLE execution_attempts (
    id UUID PRIMARY KEY,
    recovery_action_id UUID NOT NULL,
    attempt_number INT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(50) NOT NULL,
    external_execution_ref VARCHAR(255),
    dispatched_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    raw_response_payload TEXT,
    failure_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_execution_attempts_action FOREIGN KEY (recovery_action_id) REFERENCES recovery_actions (id) ON DELETE CASCADE
);

CREATE INDEX idx_execution_attempts_action_id ON execution_attempts (recovery_action_id);
CREATE INDEX idx_execution_attempts_status ON execution_attempts (status);

-- 3. Verifications: Post-action telemetry observation and convergence verification
CREATE TABLE verifications (
    id UUID PRIMARY KEY,
    execution_attempt_id UUID NOT NULL UNIQUE,
    target_resource_id UUID NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    baseline_value DOUBLE PRECISION NOT NULL,
    policy_threshold DOUBLE PRECISION NOT NULL,
    status VARCHAR(50) NOT NULL,
    observation_started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observation_ended_at TIMESTAMP WITH TIME ZONE,
    observed_samples_count INT NOT NULL DEFAULT 0,
    final_observed_value DOUBLE PRECISION,
    verification_notes TEXT,
    CONSTRAINT fk_verifications_attempt FOREIGN KEY (execution_attempt_id) REFERENCES execution_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_verifications_target_resource FOREIGN KEY (target_resource_id) REFERENCES resources (id)
);

CREATE INDEX idx_verifications_target_resource ON verifications (target_resource_id);
CREATE INDEX idx_verifications_status ON verifications (status);

-- 4. Recovery Outbox Events: Transactional outbox for reliable asynchronous dispatch
CREATE TABLE recovery_outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL UNIQUE,
    payload TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    last_error TEXT
);

CREATE INDEX idx_recovery_outbox_status_created ON recovery_outbox_events (status, created_at ASC);
