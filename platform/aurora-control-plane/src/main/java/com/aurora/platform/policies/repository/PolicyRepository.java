package com.aurora.platform.policies.repository;

import com.aurora.platform.policies.entity.PolicyEntity;
import com.aurora.platform.resources.entity.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, UUID> {

    List<PolicyEntity> findByTargetResourceTypeAndEnabledTrue(ResourceType targetResourceType);

    List<PolicyEntity> findByEnabledTrue();
}
