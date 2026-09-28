package com.aurora.platform.intelligence.rca.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * JPA entity representing a deterministic piece of supporting evidence for an RCA candidate.
 */
@Entity
@Table(name = "rca_evidence")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RcaEvidenceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "candidate_id", nullable = false)
    private UUID candidateId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 50)
    private RcaEvidenceType evidenceType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "metric_name")
    private String metricName;

    @Column(name = "observed_value")
    private Double observedValue;

    @Column(name = "anomaly_score")
    private Double anomalyScore;

    @Column(name = "observed_at")
    private Instant observedAt;

    @Column(name = "contribution_score", nullable = false)
    private Double contributionScore;

    @Column(name = "explanation", nullable = false, columnDefinition = "TEXT")
    private String explanation;

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
    }
}
