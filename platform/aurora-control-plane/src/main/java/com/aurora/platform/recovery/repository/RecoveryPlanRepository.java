package com.aurora.platform.recovery.repository;

import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecoveryPlanRepository extends JpaRepository<RecoveryPlanEntity, UUID> {

    Optional<RecoveryPlanEntity> findByIncidentId(UUID incidentId);

    boolean existsByIncidentId(UUID incidentId);
}
