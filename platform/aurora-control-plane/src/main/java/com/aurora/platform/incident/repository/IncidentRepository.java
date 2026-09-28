package com.aurora.platform.incident.repository;

import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {

    List<IncidentEntity> findByResourceId(UUID resourceId);

    List<IncidentEntity> findByStatus(IncidentStatus status);

    List<IncidentEntity> findBySeverity(IncidentSeverity severity);

    List<IncidentEntity> findByResourceIdAndStatus(UUID resourceId, IncidentStatus status);

    List<IncidentEntity> findByResourceIdAndStatusInOrderByDetectedAtDesc(UUID resourceId, Collection<IncidentStatus> statuses);

    List<IncidentEntity> findAllByOrderByDetectedAtDesc();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT i FROM IncidentEntity i WHERE i.id = :id")
    java.util.Optional<IncidentEntity> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    @org.springframework.data.jpa.repository.Query("SELECT i FROM IncidentEntity i WHERE i.status = :status AND i.id != :excludedId ORDER BY i.resolvedAt DESC, i.id ASC")
    List<IncidentEntity> findByStatusAndIdNotOrderByResolvedAtDescIdAsc(
            @org.springframework.data.repository.query.Param("status") IncidentStatus status,
            @org.springframework.data.repository.query.Param("excludedId") UUID excludedId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT i FROM IncidentEntity i WHERE i.resourceId = :resourceId AND i.status = :status AND i.detectedAt < :beforeDetectedAt AND (:excludedId IS NULL OR i.id != :excludedId) ORDER BY i.detectedAt DESC, i.id ASC")
    List<IncidentEntity> findHistoricalResolvedIncidents(
            @org.springframework.data.repository.query.Param("resourceId") UUID resourceId,
            @org.springframework.data.repository.query.Param("status") IncidentStatus status,
            @org.springframework.data.repository.query.Param("beforeDetectedAt") java.time.Instant beforeDetectedAt,
            @org.springframework.data.repository.query.Param("excludedId") UUID excludedId,
            org.springframework.data.domain.Pageable pageable);
}
