package com.aurora.platform.policy.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.policy.dto.CreatePolicyRequest;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.entity.PolicyEntity;
import com.aurora.platform.policy.repository.PolicyRepository;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyServiceTest {

    @Mock
    private PolicyRepository policyRepository;

    private PolicyService policyService;

    @BeforeEach
    void setUp() {
        policyService = new PolicyServiceImpl(policyRepository);
    }

    @Test
    @DisplayName("Should create policy successfully")
    void shouldCreatePolicy() {
        CreatePolicyRequest request = new CreatePolicyRequest(
                "High Error Rate Guardrail",
                ResourceType.SERVICE,
                "error_rate",
                5.0,
                "CIRCUIT_BREAKER_ENABLE",
                true
        );

        UUID policyId = UUID.randomUUID();
        PolicyEntity savedEntity = PolicyEntity.builder()
                .id(policyId)
                .name(request.name())
                .targetResourceType(request.targetResourceType())
                .metricName(request.metricName())
                .threshold(request.threshold())
                .action(request.action())
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(policyRepository.save(any(PolicyEntity.class))).thenReturn(savedEntity);

        PolicyResponse response = policyService.createPolicy(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(policyId);
        assertThat(response.name()).isEqualTo("High Error Rate Guardrail");
        assertThat(response.threshold()).isEqualTo(5.0);
        assertThat(response.enabled()).isTrue();
        verify(policyRepository).save(any(PolicyEntity.class));
    }

    @Test
    @DisplayName("Should get policy by ID")
    void shouldGetPolicyById() {
        UUID id = UUID.randomUUID();
        PolicyEntity entity = PolicyEntity.builder()
                .id(id)
                .name("Memory Alert")
                .targetResourceType(ResourceType.POD)
                .metricName("memory_utilization")
                .threshold(85.0)
                .action("SCALE_OUT")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(policyRepository.findById(id)).thenReturn(Optional.of(entity));

        PolicyResponse response = policyService.getPolicyById(id);
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Memory Alert");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when policy missing")
    void shouldThrowNotFoundWhenMissing() {
        UUID missingId = UUID.randomUUID();
        when(policyRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.getPolicyById(missingId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Policy with ID '" + missingId + "' not found");
    }

    @Test
    @DisplayName("Should update policy status")
    void shouldUpdatePolicyStatus() {
        UUID id = UUID.randomUUID();
        PolicyEntity entity = PolicyEntity.builder()
                .id(id)
                .name("CPU Policy")
                .targetResourceType(ResourceType.SERVER)
                .metricName("cpu_usage")
                .threshold(90.0)
                .action("NOTIFY")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(policyRepository.findById(id)).thenReturn(Optional.of(entity));
        when(policyRepository.save(any(PolicyEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PolicyResponse response = policyService.updatePolicyStatus(id, false);
        assertThat(response.enabled()).isFalse();
        verify(policyRepository).save(entity);
    }
}
