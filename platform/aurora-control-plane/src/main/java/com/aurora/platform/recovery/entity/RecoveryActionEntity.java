package com.aurora.platform.recovery.entity;

import com.aurora.platform.resource.entity.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recovery_actions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecoveryActionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recovery_plan_id", nullable = false)
    private RecoveryPlanEntity recoveryPlan;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    /**
     * Authoritative, canonical target identity bound to a specific resource entity.
     */
    @Column(name = "target_resource_id", nullable = false)
    private UUID targetResourceId;

    /**
     * Read-only context snapshot of the target environment at creation time.
     */
    @Column(name = "target_environment", nullable = false, length = 50)
    private String targetEnvironment;

    /**
     * Read-only context snapshot of the target resource type at creation time.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_resource_type", nullable = false, length = 50)
    private ResourceType targetResourceType;

    /**
     * Read-only context snapshot of the target resource name at creation time.
     */
    @Column(name = "target_resource_name", nullable = false)
    private String targetResourceName;

    /**
     * Legacy target string retained for backwards compatibility.
     */
    @Column(name = "target", nullable = false)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private RecoveryActionStatus status;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // Operator Approval Audit Fields
    @Column(name = "approved_by_user_id", length = 100)
    private String approvedByUserId;

    @Column(name = "approved_by_email")
    private String approvedByEmail;

    @Column(name = "approved_by_capability", length = 50)
    private String approvedByCapability;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_reason", columnDefinition = "TEXT")
    private String approvalReason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.status == null) {
            this.status = RecoveryActionStatus.PENDING;
        }
        if (this.version == null) {
            this.version = 0L;
        }
        if (this.target == null && this.targetResourceName != null) {
            this.target = this.targetResourceName;
        } else if (this.targetResourceName == null && this.target != null) {
            this.targetResourceName = this.target;
        }
    }

    @PreUpdate
    public void preUpdate() {
        if (this.target == null && this.targetResourceName != null) {
            this.target = this.targetResourceName;
        }
    }
}
