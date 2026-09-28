-- ==============================================================================
-- AURORA Control Plane Schema Migration V4
-- Phase 2A: Resource Dependency Modeling
-- ==============================================================================

CREATE TABLE resource_dependencies (
    id UUID PRIMARY KEY,
    source_resource_id UUID NOT NULL,
    target_resource_id UUID NOT NULL,
    dependency_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_dependency_source
        FOREIGN KEY (source_resource_id)
        REFERENCES resources(id)
        ON DELETE CASCADE,

    CONSTRAINT fk_dependency_target
        FOREIGN KEY (target_resource_id)
        REFERENCES resources(id)
        ON DELETE CASCADE,

    CONSTRAINT chk_dependency_not_self
        CHECK (source_resource_id <> target_resource_id),

    CONSTRAINT uq_resource_dependency
        UNIQUE (
            source_resource_id,
            target_resource_id,
            dependency_type
        )
);

CREATE INDEX idx_resource_dependencies_source ON resource_dependencies (source_resource_id);
CREATE INDEX idx_resource_dependencies_target ON resource_dependencies (target_resource_id);
