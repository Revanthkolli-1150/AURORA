package com.aurora.platform.policy.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.entity.PolicyEntity;
import com.aurora.platform.policy.repository.PolicyRepository;
import com.aurora.platform.resource.entity.ResourceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PolicyServiceImpl implements PolicyService {

    private final PolicyRepository policyRepository;

    public PolicyServiceImpl(PolicyRepository policyRepository) {
        this.policyRepository = policyRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PolicyResponse> getActivePolicies(ResourceType targetResourceType) {
        List<PolicyEntity> policies = (targetResourceType != null)
                ? policyRepository.findByTargetResourceTypeAndEnabledTrue(targetResourceType)
                : policyRepository.findByEnabledTrue();

        return policies.stream().map(this::mapToResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyResponse getPolicyById(UUID id) {
        return policyRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Policy with ID '" + id + "' not found"));
    }

    private PolicyResponse mapToResponse(PolicyEntity entity) {
        return new PolicyResponse(
                entity.getId(),
                entity.getName(),
                entity.getTargetResourceType(),
                entity.getMetricName(),
                entity.getThreshold(),
                entity.getAction(),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
