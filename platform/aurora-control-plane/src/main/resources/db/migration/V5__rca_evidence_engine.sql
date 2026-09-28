-- ==============================================================================
-- AURORA Control Plane Schema Migration V5
-- Phase 2B: RCA Evidence Engine
-- ==============================================================================

-- 1. RCA Analyses Table
CREATE TABLE rca_analyses (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    investigated_resource_id UUID NOT NULL,
    summary TEXT NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_rca_analyses_incident
        FOREIGN KEY (incident_id)
        REFERENCES incidents(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_rca_analyses_resource
        FOREIGN KEY (investigated_resource_id)
        REFERENCES resources(id)
        ON DELETE CASCADE
);

-- 2. RCA Candidates Table
CREATE TABLE rca_candidates (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL,
    candidate_resource_id UUID NOT NULL,
    candidate_metric VARCHAR(255),
    candidate_cause TEXT NOT NULL,
    evidence_score DOUBLE PRECISION NOT NULL,
    rank INTEGER NOT NULL,
    explanation TEXT NOT NULL,
    primary_candidate BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_rca_candidates_analysis
        FOREIGN KEY (analysis_id)
        REFERENCES rca_analyses(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_rca_candidates_resource
        FOREIGN KEY (candidate_resource_id)
        REFERENCES resources(id)
        ON DELETE CASCADE
);

-- 3. RCA Evidence Table
CREATE TABLE rca_evidence (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL,
    evidence_type VARCHAR(50) NOT NULL,
    resource_id UUID NOT NULL,
    metric_name VARCHAR(255),
    observed_value DOUBLE PRECISION,
    anomaly_score DOUBLE PRECISION,
    observed_at TIMESTAMP WITH TIME ZONE,
    contribution_score DOUBLE PRECISION NOT NULL,
    explanation TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_rca_evidence_candidate
        FOREIGN KEY (candidate_id)
        REFERENCES rca_candidates(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_rca_evidence_resource
        FOREIGN KEY (resource_id)
        REFERENCES resources(id)
        ON DELETE CASCADE
);

-- 4. Indexes for Efficient Querying and Traversal
CREATE INDEX idx_rca_analyses_incident ON rca_analyses (incident_id);
CREATE INDEX idx_rca_analyses_created ON rca_analyses (created_at DESC);

CREATE INDEX idx_rca_candidates_analysis ON rca_candidates (analysis_id);
CREATE INDEX idx_rca_candidates_resource ON rca_candidates (candidate_resource_id);
CREATE INDEX idx_rca_candidates_analysis_rank ON rca_candidates (analysis_id, rank ASC);

CREATE INDEX idx_rca_evidence_candidate ON rca_evidence (candidate_id);
CREATE INDEX idx_rca_evidence_resource ON rca_evidence (resource_id);
CREATE INDEX idx_rca_evidence_observed ON rca_evidence (observed_at);
