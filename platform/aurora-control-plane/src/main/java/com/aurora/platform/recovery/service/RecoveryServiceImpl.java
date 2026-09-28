package com.aurora.platform.recovery.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.recovery.dto.RecoveryActionResponse;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RecoveryServiceImpl implements RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryServiceImpl.class);

    private final RecoveryPlanRepository recoveryPlanRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final IncidentService incidentService;

    public RecoveryServiceImpl(RecoveryPlanRepository recoveryPlanRepository,
                               RecoveryActionRepository recoveryActionRepository,
                               IncidentService incidentService) {
        this.recoveryPlanRepository = recoveryPlanRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.incidentService = incidentService;
    }

    @Override
    @Transactional(readOnly = true)
    public RecoveryPlanResponse getRecoveryPlanByIncidentId(UUID incidentId) {
        if (!incidentService.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident with ID '" + incidentId + "' not found");
        }

        RecoveryPlanEntity plan = recoveryPlanRepository.findByIncidentId(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery plan for incident ID '" + incidentId + "' not found"));

        List<RecoveryActionEntity> actions = recoveryActionRepository.findByRecoveryPlanId(plan.getId());
        return mapToResponse(plan, actions);
    }

    @Override
    @Transactional
    public RecoveryPlanResponse createRecoveryPlan(UUID incidentId,
                                                   String reasoning,
                                                   Double confidence,
                                                   RecoveryRisk risk,
                                                   Boolean approvalRequired,
                                                   List<RecoveryActionEntity> actions) {
        log.info("Creating recovery plan for incident ID {}", incidentId);

        if (!incidentService.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident with ID '" + incidentId + "' not found");
        }

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .incidentId(incidentId)
                .reasoning(reasoning)
                .confidence(confidence)
                .risk(risk)
                .approvalRequired(approvalRequired != null ? approvalRequired : true)
                .build();

        RecoveryPlanEntity savedPlan = recoveryPlanRepository.save(plan);

        if (actions != null && !actions.isEmpty()) {
            for (RecoveryActionEntity action : actions) {
                action.setRecoveryPlan(savedPlan);
                recoveryActionRepository.save(action);
            }
        }

        List<RecoveryActionEntity> savedActions = recoveryActionRepository.findByRecoveryPlanId(savedPlan.getId());
        return mapToResponse(savedPlan, savedActions);
    }

    private RecoveryPlanResponse mapToResponse(RecoveryPlanEntity plan, List<RecoveryActionEntity> actions) {
        List<RecoveryActionResponse> actionResponses = actions.stream()
                .map(a -> new RecoveryActionResponse(
                        a.getId(),
                        a.getRecoveryPlan().getId(),
                        a.getActionType(),
                        a.getTarget(),
                        a.getStatus(),
                        a.getResult(),
                        a.getStartedAt(),
                        a.getCompletedAt()
                ))
                .toList();

        return new RecoveryPlanResponse(
                plan.getId(),
                plan.getIncidentId(),
                plan.getReasoning(),
                plan.getConfidence(),
                plan.getRisk(),
                plan.getApprovalRequired(),
                actionResponses,
                plan.getCreatedAt()
        );
    }
}
