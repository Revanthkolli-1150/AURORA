package com.aurora.platform.incidents.repository;

import com.aurora.platform.incidents.entity.IncidentEntity;
import com.aurora.platform.incidents.entity.IncidentSeverity;
import com.aurora.platform.incidents.entity.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {

    List<IncidentEntity> findByResourceId(UUID resourceId);

    List<IncidentEntity> findByStatus(IncidentStatus status);

    List<IncidentEntity> findBySeverity(IncidentSeverity severity);

    List<IncidentEntity> findByResourceIdAndStatus(UUID resourceId, IncidentStatus status);

    List<IncidentEntity> findAllByOrderByDetectedAtDesc();
}
