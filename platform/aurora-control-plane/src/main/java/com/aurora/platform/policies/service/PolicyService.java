package com.aurora.platform.policies.service;

import com.aurora.platform.policies.dto.PolicyResponse;
import com.aurora.platform.resources.entity.ResourceType;

import java.util.List;
import java.util.UUID;

public interface PolicyService {

    List<PolicyResponse> getActivePolicies(ResourceType targetResourceType);

    PolicyResponse getPolicyById(UUID id);
}
