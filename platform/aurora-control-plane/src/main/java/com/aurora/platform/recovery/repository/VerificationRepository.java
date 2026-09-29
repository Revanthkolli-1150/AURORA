package com.aurora.platform.recovery.repository;

import com.aurora.platform.recovery.entity.VerificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VerificationRepository extends JpaRepository<VerificationEntity, UUID> {

    Optional<VerificationEntity> findByExecutionAttemptId(UUID executionAttemptId);

    List<VerificationEntity> findByTargetResourceId(UUID targetResourceId);
}
