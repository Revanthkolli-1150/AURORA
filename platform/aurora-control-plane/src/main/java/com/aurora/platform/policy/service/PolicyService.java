package com.aurora.platform.policy.service;

import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.resource.entity.ResourceType;

import java.util.List;
import java.util.UUID;

public interface PolicyService {

    List<PolicyResponse> getActivePolicies(ResourceType targetResourceType);

    List<PolicyResponse> getAllPolicies(ResourceType targetResourceType, Boolean enabledOnly);

    PolicyResponse getPolicyById(UUID id);

    PolicyResponse createPolicy(com.aurora.platform.policy.dto.CreatePolicyRequest request);

    PolicyResponse updatePolicyStatus(UUID id, boolean enabled);
}
