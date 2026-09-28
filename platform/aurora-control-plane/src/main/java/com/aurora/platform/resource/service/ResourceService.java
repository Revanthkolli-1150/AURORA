package com.aurora.platform.resource.service;

import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;

import java.util.List;
import java.util.UUID;

public interface ResourceService {

    ResourceResponse createResource(CreateResourceRequest request);

    ResourceResponse getResourceById(UUID id);

    List<ResourceResponse> getAllResources(String environment, ResourceType type, ResourceStatus status);

    boolean existsById(UUID id);
}
