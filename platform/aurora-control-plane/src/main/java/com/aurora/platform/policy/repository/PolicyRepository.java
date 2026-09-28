package com.aurora.platform.policy.repository;

import com.aurora.platform.policy.entity.PolicyEntity;
import com.aurora.platform.resource.entity.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PolicyRepository extends JpaRepository<PolicyEntity, UUID> {

    List<PolicyEntity> findByTargetResourceTypeAndEnabledTrue(ResourceType targetResourceType);

    List<PolicyEntity> findByEnabledTrue();
}
