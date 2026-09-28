package com.aurora.platform.resource.repository;

import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResourceRepository extends JpaRepository<ResourceEntity, UUID> {

    Optional<ResourceEntity> findByNameAndEnvironment(String name, String environment);

    boolean existsByNameAndEnvironment(String name, String environment);

    List<ResourceEntity> findByEnvironment(String environment);

    List<ResourceEntity> findByType(ResourceType type);

    List<ResourceEntity> findByStatus(ResourceStatus status);
}
