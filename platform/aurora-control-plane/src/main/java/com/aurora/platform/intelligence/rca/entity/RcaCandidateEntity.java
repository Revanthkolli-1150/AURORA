package com.aurora.platform.intelligence.rca.entity;

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
 * JPA entity representing a candidate root cause identified during RCA.
 */
@Entity
@Table(name = "rca_candidates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RcaCandidateEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "analysis_id", nullable = false)
    private UUID analysisId;

    @Column(name = "candidate_resource_id", nullable = false)
    private UUID candidateResourceId;

    @Column(name = "candidate_metric")
    private String candidateMetric;

    @Column(name = "candidate_cause", nullable = false, columnDefinition = "TEXT")
    private String candidateCause;

    @Column(name = "evidence_score", nullable = false)
    private Double evidenceScore;

    @Column(name = "rank", nullable = false)
    private Integer rank;

    @Column(name = "explanation", nullable = false, columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "primary_candidate", nullable = false)
    private Boolean primaryCandidate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.primaryCandidate == null) {
            this.primaryCandidate = false;
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
