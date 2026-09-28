-- ==============================================================================
-- AURORA Control Plane Schema Migration V6
-- Phase 3: LLM Operator Summaries & Runbook Synthesis
-- Target: PostgreSQL 14+ (Standard SQL compatible with H2 in PostgreSQL mode)
-- ==============================================================================

CREATE TABLE incident_narratives (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    rca_analysis_id UUID NOT NULL,
    headline VARCHAR(255) NOT NULL,
    executive_summary TEXT NOT NULL,
    observed_symptoms TEXT NOT NULL,
    root_cause_explanation TEXT NOT NULL,
    secondary_hypotheses TEXT NOT NULL,
    historical_context TEXT,
    investigation_steps TEXT NOT NULL,
    caveats TEXT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    generation_duration_ms INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_incident_narratives_incident
        FOREIGN KEY (incident_id)
        REFERENCES incidents(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_incident_narratives_rca
        FOREIGN KEY (rca_analysis_id)
        REFERENCES rca_analyses(id)
        ON DELETE CASCADE,

    CONSTRAINT uq_incident_narratives_analysis
        UNIQUE (rca_analysis_id)
);

-- Indexes for efficient lookups
CREATE INDEX idx_incident_narratives_incident ON incident_narratives (incident_id);
CREATE INDEX idx_incident_narratives_created ON incident_narratives (created_at DESC);
