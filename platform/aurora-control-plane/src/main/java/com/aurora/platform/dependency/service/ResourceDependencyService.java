package com.aurora.platform.dependency.service;

import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;

import java.util.List;
import java.util.UUID;

/**
 * Service managing directed dependency relationships between monitored resources.
 */
public interface ResourceDependencyService {

    /**
     * Creates a directed dependency where sourceResourceId DEPENDS_ON targetResourceId.
     */
    ResourceDependencyResponse createDependency(UUID sourceResourceId, CreateResourceDependencyRequest request);

    /**
     * Retrieves all outgoing dependencies where the given resource is the source (resources it depends on).
     */
    List<ResourceDependencyResponse> getDependencies(UUID resourceId);

    /**
     * Retrieves all incoming dependencies where the given resource is the target (resources that depend on it).
     */
    List<ResourceDependencyResponse> getDependents(UUID resourceId);

    /**
     * Deletes a directed dependency belonging to the specified source resource.
     */
    void deleteDependency(UUID resourceId, UUID dependencyId);
}
