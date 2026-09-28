package com.aurora.platform.intelligence.rca.infrastructure;

import com.aurora.platform.intelligence.rca.application.ResourceQuery;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.repository.ResourceRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class ResourceQueryAdapter implements ResourceQuery {

    private final ResourceRepository resourceRepository;

    public ResourceQueryAdapter(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Override
    public Optional<ResourceEntity> findResource(UUID resourceId) {
        return resourceRepository.findById(resourceId);
    }

    @Override
    public boolean existsResource(UUID resourceId) {
        return resourceRepository.existsById(resourceId);
    }
}
