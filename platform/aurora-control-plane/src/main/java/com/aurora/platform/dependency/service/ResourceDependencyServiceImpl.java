package com.aurora.platform.dependency.service;

import com.aurora.platform.common.exception.ConflictException;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.resource.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ResourceDependencyServiceImpl implements ResourceDependencyService {

    private static final Logger log = LoggerFactory.getLogger(ResourceDependencyServiceImpl.class);

    private final ResourceDependencyRepository dependencyRepository;
    private final ResourceService resourceService;

    public ResourceDependencyServiceImpl(
            ResourceDependencyRepository dependencyRepository,
            ResourceService resourceService) {
        this.dependencyRepository = dependencyRepository;
        this.resourceService = resourceService;
    }

    @Override
    @Transactional
    public ResourceDependencyResponse createDependency(UUID sourceResourceId, CreateResourceDependencyRequest request) {
        log.info("Creating dependency: resource {} -> resource {} (type: {})",
                sourceResourceId, request.targetResourceId(), request.dependencyType());

        // Rule 1: Source resource must exist
        if (!resourceService.existsById(sourceResourceId)) {
            throw new ResourceNotFoundException("Source resource with ID '" + sourceResourceId + "' not found");
        }

        // Rule 2: Target resource must exist
        if (!resourceService.existsById(request.targetResourceId())) {
            throw new ResourceNotFoundException("Target resource with ID '" + request.targetResourceId() + "' not found");
        }

        // Rule 3: Self-dependency is invalid
        if (sourceResourceId.equals(request.targetResourceId())) {
            throw new ValidationException("A resource cannot depend on itself");
        }

        // Rule 5: Dependency type must currently be DEPENDS_ON
        if (request.dependencyType() != DependencyType.DEPENDS_ON) {
            throw new ValidationException("Only DEPENDS_ON dependency type is supported in Phase 2A");
        }

        // Rule 4: Duplicate directed dependency is invalid (409 Conflict)
        if (dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(
                sourceResourceId, request.targetResourceId(), request.dependencyType())) {
            throw new ConflictException(String.format(
                    "Dependency already exists from resource '%s' to resource '%s' with type '%s'",
                    sourceResourceId, request.targetResourceId(), request.dependencyType()));
        }

        ResourceDependencyEntity entity = ResourceDependencyEntity.builder()
                .sourceResourceId(sourceResourceId)
                .targetResourceId(request.targetResourceId())
                .dependencyType(request.dependencyType())
                .build();

        ResourceDependencyEntity saved = dependencyRepository.save(entity);
        log.info("Successfully created resource dependency with ID: {}", saved.getId());
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceDependencyResponse> getDependencies(UUID resourceId) {
        log.debug("Retrieving outgoing dependencies for resource {}", resourceId);

        if (!resourceService.existsById(resourceId)) {
            throw new ResourceNotFoundException("Resource with ID '" + resourceId + "' not found");
        }

        return dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(resourceId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceDependencyResponse> getDependents(UUID resourceId) {
        log.debug("Retrieving incoming dependents for resource {}", resourceId);

        if (!resourceService.existsById(resourceId)) {
            throw new ResourceNotFoundException("Resource with ID '" + resourceId + "' not found");
        }

        return dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(resourceId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteDependency(UUID resourceId, UUID dependencyId) {
        log.info("Deleting dependency {} for source resource {}", dependencyId, resourceId);

        if (!resourceService.existsById(resourceId)) {
            throw new ResourceNotFoundException("Resource with ID '" + resourceId + "' not found");
        }

        ResourceDependencyEntity dependency = dependencyRepository.findById(dependencyId)
                .orElseThrow(() -> new ResourceNotFoundException("Dependency with ID '" + dependencyId + "' not found"));

        if (!dependency.getSourceResourceId().equals(resourceId)) {
            throw new ResourceNotFoundException(String.format(
                    "Dependency with ID '%s' does not belong to resource '%s'", dependencyId, resourceId));
        }

        dependencyRepository.delete(dependency);
        log.info("Successfully deleted dependency {}", dependencyId);
    }

    private ResourceDependencyResponse mapToResponse(ResourceDependencyEntity entity) {
        return new ResourceDependencyResponse(
                entity.getId(),
                entity.getSourceResourceId(),
                entity.getTargetResourceId(),
                entity.getDependencyType(),
                entity.getCreatedAt()
        );
    }
}
