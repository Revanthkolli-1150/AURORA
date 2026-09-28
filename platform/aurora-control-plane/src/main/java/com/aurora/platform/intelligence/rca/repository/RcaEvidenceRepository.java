package com.aurora.platform.intelligence.rca.repository;

import com.aurora.platform.intelligence.rca.entity.RcaEvidenceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RcaEvidenceRepository extends JpaRepository<RcaEvidenceEntity, UUID> {

    List<RcaEvidenceEntity> findByCandidateIdOrderByContributionScoreDesc(UUID candidateId);

    List<RcaEvidenceEntity> findByCandidateId(UUID candidateId);

    List<RcaEvidenceEntity> findByResourceId(UUID resourceId);
}
