package com.aurora.platform.resources.service;

import com.aurora.platform.resources.dto.CreateResourceRequest;
import com.aurora.platform.resources.dto.ResourceResponse;
import com.aurora.platform.resources.entity.ResourceStatus;
import com.aurora.platform.resources.entity.ResourceType;

import java.util.List;
import java.util.UUID;

public interface ResourceService {

    ResourceResponse createResource(CreateResourceRequest request);

    ResourceResponse getResourceById(UUID id);

    List<ResourceResponse> getAllResources(String environment, ResourceType type, ResourceStatus status);

    boolean existsById(UUID id);
}
