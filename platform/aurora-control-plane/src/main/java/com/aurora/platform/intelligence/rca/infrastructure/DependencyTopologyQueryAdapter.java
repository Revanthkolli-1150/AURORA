package com.aurora.platform.intelligence.rca.infrastructure;

import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.intelligence.rca.application.DependencyTopologyQuery;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DependencyTopologyQueryAdapter implements DependencyTopologyQuery {

    private final ResourceDependencyRepository dependencyRepository;

    public DependencyTopologyQueryAdapter(ResourceDependencyRepository dependencyRepository) {
        this.dependencyRepository = dependencyRepository;
    }

    @Override
    public List<UUID> findDirectDependencies(UUID sourceResourceId) {
        return dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(sourceResourceId)
                .stream()
                .map(ResourceDependencyEntity::getTargetResourceId)
                .toList();
    }

    @Override
    public List<UUID> findDirectDependents(UUID targetResourceId) {
        return dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(targetResourceId)
                .stream()
                .map(ResourceDependencyEntity::getSourceResourceId)
                .toList();
    }
}
