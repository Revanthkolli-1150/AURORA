package com.aurora.platform.incident.repository;

import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IncidentAnomalyEvidenceRepository extends JpaRepository<IncidentAnomalyEvidenceEntity, UUID> {

    List<IncidentAnomalyEvidenceEntity> findByIncidentIdOrderByObservedAtAsc(UUID incidentId);

    List<IncidentAnomalyEvidenceEntity> findByIncidentIdIn(java.util.Collection<UUID> incidentIds);

    List<IncidentAnomalyEvidenceEntity> findByResourceIdOrderByObservedAtDesc(UUID resourceId);

    List<IncidentAnomalyEvidenceEntity> findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(UUID resourceId, java.time.Instant start, java.time.Instant end);

    Optional<IncidentAnomalyEvidenceEntity> findFirstByIncidentIdOrderByObservedAtDesc(UUID incidentId);

    long countByIncidentId(UUID incidentId);
}
