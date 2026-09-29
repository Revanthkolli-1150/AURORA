package com.aurora.platform.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerificationEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "execution_attempt_id", nullable = false, unique = true)
    private ExecutionAttemptEntity executionAttempt;

    @Column(name = "target_resource_id", nullable = false)
    private UUID targetResourceId;

    @Column(name = "metric_name", nullable = false)
    private String metricName;

    @Column(name = "baseline_value", nullable = false)
    private Double baselineValue;

    @Column(name = "policy_threshold", nullable = false)
    private Double policyThreshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private VerificationStatus status;

    @Column(name = "observation_started_at", nullable = false)
    private Instant observationStartedAt;

    @Column(name = "observation_ended_at")
    private Instant observationEndedAt;

    @Column(name = "observed_samples_count", nullable = false)
    private int observedSamplesCount;

    @Column(name = "final_observed_value")
    private Double finalObservedValue;

    @Column(name = "verification_notes", columnDefinition = "TEXT")
    private String verificationNotes;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.observationStartedAt == null) {
            this.observationStartedAt = Instant.now();
        }
        if (this.status == null) {
            this.status = VerificationStatus.SCHEDULED;
        }
    }
}
