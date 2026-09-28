package com.aurora.platform.intelligence.rca.repository;

import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RcaAnalysisRepository extends JpaRepository<RcaAnalysisEntity, UUID> {

    List<RcaAnalysisEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    List<RcaAnalysisEntity> findByIncidentIdInOrderByCreatedAtDesc(java.util.Collection<UUID> incidentIds);

    Optional<RcaAnalysisEntity> findFirstByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    Optional<RcaAnalysisEntity> findByIdAndIncidentId(UUID id, UUID incidentId);
}
