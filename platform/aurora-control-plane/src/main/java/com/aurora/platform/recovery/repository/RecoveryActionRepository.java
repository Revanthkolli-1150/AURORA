package com.aurora.platform.recovery.repository;

import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecoveryActionRepository extends JpaRepository<RecoveryActionEntity, UUID> {

    List<RecoveryActionEntity> findByRecoveryPlanId(UUID recoveryPlanId);

    List<RecoveryActionEntity> findByTargetResourceIdAndActionType(UUID targetResourceId, String actionType);

    List<RecoveryActionEntity> findByTargetResourceId(UUID targetResourceId);

    List<RecoveryActionEntity> findByTargetAndActionType(String target, String actionType);

    List<RecoveryActionEntity> findByTarget(String target);
}
