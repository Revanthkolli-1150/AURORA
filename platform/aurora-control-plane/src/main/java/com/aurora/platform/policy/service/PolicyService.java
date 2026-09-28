package com.aurora.platform.policy.service;

import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.resource.entity.ResourceType;

import java.util.List;
import java.util.UUID;

public interface PolicyService {

    List<PolicyResponse> getActivePolicies(ResourceType targetResourceType);

    PolicyResponse getPolicyById(UUID id);
}
