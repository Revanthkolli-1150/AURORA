package com.aurora.platform.intelligence.rca.application;

import java.util.List;
import java.util.UUID;

/**
 * Application query port for accessing topology dependency relationships required by RCA.
 */
public interface DependencyTopologyQuery {

    /**
     * Retrieves the direct upstream dependencies (target resource IDs) that the given resource depends on.
     */
    List<UUID> findDirectDependencies(UUID sourceResourceId);

    /**
     * Retrieves the direct downstream dependents (source resource IDs) that depend on the given resource.
     */
    List<UUID> findDirectDependents(UUID targetResourceId);
}
