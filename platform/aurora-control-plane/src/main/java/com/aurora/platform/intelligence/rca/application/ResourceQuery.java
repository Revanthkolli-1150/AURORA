package com.aurora.platform.intelligence.rca.application;

import com.aurora.platform.resource.entity.ResourceEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Application query port for accessing resource domain information required by RCA.
 */
public interface ResourceQuery {

    Optional<ResourceEntity> findResource(UUID resourceId);

    boolean existsResource(UUID resourceId);
}
