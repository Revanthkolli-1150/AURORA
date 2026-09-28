package com.aurora.platform.dependency.repository;

import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ResourceDependencyRepository extends JpaRepository<ResourceDependencyEntity, UUID> {

    /**
     * Finds all outgoing dependencies where resource is the source.
     * Answers: "What resources does this resource depend on?"
     */
    List<ResourceDependencyEntity> findBySourceResourceIdOrderByCreatedAtAsc(UUID sourceResourceId);

    List<ResourceDependencyEntity> findBySourceResourceIdIn(java.util.Collection<UUID> sourceResourceIds);

    /**
     * Finds all incoming dependencies where resource is the target.
     * Answers: "What resources depend on this resource?"
     */
    List<ResourceDependencyEntity> findByTargetResourceIdOrderByCreatedAtAsc(UUID targetResourceId);

    List<ResourceDependencyEntity> findByTargetResourceIdIn(java.util.Collection<UUID> targetResourceIds);

    /**
     * Checks if a directed relationship of a specific type already exists between source and target.
     */
    boolean existsBySourceResourceIdAndTargetResourceIdAndDependencyType(
            UUID sourceResourceId, UUID targetResourceId, DependencyType dependencyType);

    /**
     * Finds a dependency by ID and ensures it belongs to the given source resource.
     */
    Optional<ResourceDependencyEntity> findByIdAndSourceResourceId(UUID id, UUID sourceResourceId);
}
