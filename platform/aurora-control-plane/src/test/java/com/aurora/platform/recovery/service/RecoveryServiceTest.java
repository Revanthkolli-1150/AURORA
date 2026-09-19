package com.aurora.platform.recovery.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incidents.service.IncidentService;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryPlanRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryServiceTest {

    @Mock
    private RecoveryPlanRepository recoveryPlanRepository;

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private IncidentService incidentService;

    private RecoveryService recoveryService;

    @BeforeEach
    void setUp() {
        recoveryService = new RecoveryServiceImpl(recoveryPlanRepository, recoveryActionRepository, incidentService);
    }

    @Test
    @DisplayName("Should retrieve recovery plan and actions for a valid incident")
    void shouldGetRecoveryPlanForIncident() {
        UUID incidentId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        when(incidentService.existsById(incidentId)).thenReturn(true);

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .id(planId)
                .incidentId(incidentId)
                .reasoning("Restart degraded pod replicas to clear thread starvation")
                .confidence(0.92)
                .risk(RecoveryRisk.LOW)
                .approvalRequired(false)
                .createdAt(Instant.now())
                .build();

        RecoveryActionEntity action = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .recoveryPlan(plan)
                .actionType("RESTART_POD")
                .target("auth-service-pod-xyz")
                .status(RecoveryActionStatus.PENDING)
                .build();

        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.of(plan));
        when(recoveryActionRepository.findByRecoveryPlanId(planId)).thenReturn(List.of(action));

        RecoveryPlanResponse response = recoveryService.getRecoveryPlanByIncidentId(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(planId);
        assertThat(response.incidentId()).isEqualTo(incidentId);
        assertThat(response.risk()).isEqualTo(RecoveryRisk.LOW);
        assertThat(response.actions()).hasSize(1);
        assertThat(response.actions().get(0).actionType()).isEqualTo("RESTART_POD");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when incident does not exist")
    void shouldThrowNotFoundWhenIncidentMissing() {
        UUID missingIncidentId = UUID.randomUUID();
        when(incidentService.existsById(missingIncidentId)).thenReturn(false);

        assertThatThrownBy(() -> recoveryService.getRecoveryPlanByIncidentId(missingIncidentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incident with ID '" + missingIncidentId + "' not found");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when recovery plan does not exist for incident")
    void shouldThrowNotFoundWhenPlanMissing() {
        UUID incidentId = UUID.randomUUID();
        when(incidentService.existsById(incidentId)).thenReturn(true);
        when(recoveryPlanRepository.findByIncidentId(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recoveryService.getRecoveryPlanByIncidentId(incidentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Recovery plan for incident ID '" + incidentId + "' not found");
    }
}
