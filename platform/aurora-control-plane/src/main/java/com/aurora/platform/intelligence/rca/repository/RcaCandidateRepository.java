package com.aurora.platform.intelligence.rca.repository;

import com.aurora.platform.intelligence.rca.entity.RcaCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RcaCandidateRepository extends JpaRepository<RcaCandidateEntity, UUID> {

    List<RcaCandidateEntity> findByAnalysisIdOrderByRankAsc(UUID analysisId);

    List<RcaCandidateEntity> findByAnalysisIdInOrderByRankAsc(java.util.Collection<UUID> analysisIds);

    List<RcaCandidateEntity> findByAnalysisId(UUID analysisId);

    List<RcaCandidateEntity> findByCandidateResourceId(UUID candidateResourceId);
}
