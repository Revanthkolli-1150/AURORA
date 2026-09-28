package com.aurora.platform.intelligence.narrative.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a persisted incident narrative summary and its generation provenance.
 */
@Entity
@Table(name = "incident_narratives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentNarrativeEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "incident_id", nullable = false, updatable = false)
    private UUID incidentId;

    @Column(name = "rca_analysis_id", nullable = false, updatable = false, unique = true)
    private UUID rcaAnalysisId;

    @Column(name = "headline", nullable = false, length = 255)
    private String headline;

    @Column(name = "executive_summary", nullable = false, columnDefinition = "TEXT")
    private String executiveSummary;

    @Column(name = "observed_symptoms", nullable = false, columnDefinition = "TEXT")
    private String observedSymptoms;

    @Column(name = "root_cause_explanation", nullable = false, columnDefinition = "TEXT")
    private String rootCauseExplanation;

    @Column(name = "secondary_hypotheses", nullable = false, columnDefinition = "TEXT")
    private String secondaryHypotheses;

    @Column(name = "historical_context", columnDefinition = "TEXT")
    private String historicalContext;

    @Column(name = "investigation_steps", nullable = false, columnDefinition = "TEXT")
    private String investigationSteps;

    @Column(name = "caveats", nullable = false, columnDefinition = "TEXT")
    private String caveats;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    @Column(name = "fallback_used", nullable = false)
    private Boolean fallbackUsed;

    @Column(name = "generation_duration_ms", nullable = false)
    private Integer generationDurationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.fallbackUsed == null) {
            this.fallbackUsed = false;
        }
    }
}
