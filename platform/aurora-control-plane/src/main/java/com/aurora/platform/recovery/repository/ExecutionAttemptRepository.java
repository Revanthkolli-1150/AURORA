package com.aurora.platform.recovery.repository;

import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExecutionAttemptRepository extends JpaRepository<ExecutionAttemptEntity, UUID> {

    Optional<ExecutionAttemptEntity> findByIdempotencyKey(String idempotencyKey);

    List<ExecutionAttemptEntity> findByRecoveryActionIdOrderByAttemptNumberAsc(UUID recoveryActionId);

    Optional<ExecutionAttemptEntity> findTopByRecoveryActionIdOrderByAttemptNumberDesc(UUID recoveryActionId);

    long countByRecoveryActionId(UUID recoveryActionId);
}
