package com.aurora.platform.recovery.repository;

import com.aurora.platform.recovery.entity.OutboxEventStatus;
import com.aurora.platform.recovery.entity.RecoveryOutboxEventEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecoveryOutboxEventRepository extends JpaRepository<RecoveryOutboxEventEntity, UUID> {

    Optional<RecoveryOutboxEventEntity> findByIdempotencyKey(String idempotencyKey);

    List<RecoveryOutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status);

    List<RecoveryOutboxEventEntity> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM RecoveryOutboxEventEntity e WHERE e.status = :status ORDER BY e.createdAt ASC")
    List<RecoveryOutboxEventEntity> findPendingForUpdate(@Param("status") OutboxEventStatus status, Pageable pageable);
}
