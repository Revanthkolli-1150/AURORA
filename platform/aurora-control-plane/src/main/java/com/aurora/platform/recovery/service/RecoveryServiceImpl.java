package com.aurora.platform.recovery.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.infrastructure.security.AuthenticatedOperator;
import com.aurora.platform.infrastructure.security.RecoveryCapability;
import com.aurora.platform.infrastructure.security.SecurityUtils;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.dto.RcaEvidenceResponse;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.service.PolicyService;
import com.aurora.platform.recovery.dto.CreateRecoveryActionRequest;
import com.aurora.platform.recovery.dto.CreateRecoveryPlanRequest;
import com.aurora.platform.recovery.dto.RecoveryActionResponse;
import com.aurora.platform.recovery.dto.RecoveryPlanResponse;
import com.aurora.platform.recovery.entity.OutboxEventStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryOutboxEventEntity;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryOutboxEventRepository;
import com.aurora.platform.recovery.repository.RecoveryPlanRepository;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RecoveryServiceImpl implements RecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryServiceImpl.class);

    private final RecoveryPlanRepository recoveryPlanRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final IncidentService incidentService;
    private final RcaAnalysisService rcaAnalysisService;
    private final ResourceService resourceService;
    private final PolicyService policyService;
    private final RecoveryOutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();

    public record AntiFlappingEvaluation(
            boolean inCooldown,
            long failureCount,
            String suppressionReason
    ) {}

    public record PolicyPreFlightEvaluation(
            boolean allowed,
            PolicyResponse matchedPolicy,
            String blockReason
    ) {}

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Autowired
    public RecoveryServiceImpl(
            RecoveryPlanRepository recoveryPlanRepository,
            RecoveryActionRepository recoveryActionRepository,
            IncidentService incidentService,
            @Autowired(required = false) RcaAnalysisService rcaAnalysisService,
            @Autowired(required = false) ResourceService resourceService,
            @Autowired(required = false) PolicyService policyService,
            @Autowired(required = false) RecoveryOutboxEventRepository outboxEventRepository,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.recoveryPlanRepository = recoveryPlanRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.incidentService = incidentService;
        this.rcaAnalysisService = rcaAnalysisService;
        this.resourceService = resourceService;
        this.policyService = policyService;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public RecoveryServiceImpl(
            RecoveryPlanRepository recoveryPlanRepository,
            RecoveryActionRepository recoveryActionRepository,
            IncidentService incidentService,
            RcaAnalysisService rcaAnalysisService,
            ResourceService resourceService,
            PolicyService policyService) {
        this(recoveryPlanRepository, recoveryActionRepository, incidentService, rcaAnalysisService, resourceService, policyService, null, null);
    }

    public RecoveryServiceImpl(
            RecoveryPlanRepository recoveryPlanRepository,
            RecoveryActionRepository recoveryActionRepository,
            IncidentService incidentService) {
        this(recoveryPlanRepository, recoveryActionRepository, incidentService, null, null, null, null, null);
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
    public RecoveryPlanResponse generateRecoveryPlan(UUID incidentId) {
        log.info("Generating deterministic recovery plan for incident ID {}", incidentId);

        // 1. Verify incident exists
        IncidentResponse incident = incidentService.getIncidentById(incidentId);

        // 2. Idempotency: return existing plan if already generated
        Optional<RecoveryPlanEntity> existingPlan = recoveryPlanRepository.findByIncidentId(incidentId);
        if (existingPlan.isPresent()) {
            log.info("Returning existing recovery plan {} for incident ID {}", existingPlan.get().getId(), incidentId);
            List<RecoveryActionEntity> existingActions = recoveryActionRepository.findByRecoveryPlanId(existingPlan.get().getId());
            return mapToResponse(existingPlan.get(), existingActions);
        }

        // 3. Obtain latest completed deterministic RCA analysis
        if (rcaAnalysisService == null) {
            throw new IllegalStateException("RcaAnalysisService is required to synthesize recovery plans");
        }

        RcaAnalysisResponse rcaAnalysis = rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId);
        if (rcaAnalysis == null) {
            throw new ResourceNotFoundException("No completed RCA analysis found for incident ID: " + incidentId);
        }

        // 4. Identify primary candidate root cause
        RcaCandidateResponse primaryCandidate = rcaAnalysis.candidates().stream()
                .filter(c -> Boolean.TRUE.equals(c.primaryCandidate()))
                .findFirst()
                .orElse(null);

        boolean isConclusive = rcaAnalysis.status() == RcaAnalysisStatus.COMPLETED
                && rcaAnalysis.confidence() != null
                && rcaAnalysis.confidence() >= 0.30
                && primaryCandidate != null;

        String reasoning;
        Double confidence = rcaAnalysis.confidence() != null ? rcaAnalysis.confidence() : 0.0;
        RecoveryRisk risk;
        boolean approvalRequired;
        List<RecoveryActionEntity> actionsToSave = new ArrayList<>();

        if (!isConclusive) {
            // Insufficient evidence: generate advisory plan recommending manual triage
            reasoning = String.format(
                    "RCA analysis %s indicated insufficient diagnostic evidence or low confidence (%.2f). " +
                            "Automated recovery proposal suspended; manual diagnostic investigation required.",
                    rcaAnalysis.id(),
                    confidence
            );
            risk = RecoveryRisk.CRITICAL;
            approvalRequired = true;

            UUID targetResId = incident.resourceId();
            ResourceResponse incResource = null;
            if (resourceService != null && targetResId != null) {
                try {
                    incResource = resourceService.getResourceById(targetResId);
                } catch (Exception ignored) {}
            }

            String targetName = incResource != null ? incResource.name() : (targetResId != null ? targetResId.toString() : "unknown");
            String env = incResource != null ? incResource.environment() : "production";
            ResourceType rType = incResource != null ? incResource.type() : ResourceType.SERVICE;

            actionsToSave.add(RecoveryActionEntity.builder()
                    .actionType("MANUAL_INVESTIGATION")
                    .targetResourceId(targetResId)
                    .targetEnvironment(env)
                    .targetResourceType(rType)
                    .targetResourceName(targetName)
                    .target(targetName)
                    .status(RecoveryActionStatus.PENDING)
                    .result("Operator manual triage required; automated recovery withheld.")
                    .build());
        } else {
            // Conclusive diagnosis: synthesize deterministic, safe, ordered actions
            UUID targetResourceId = primaryCandidate.candidateResourceId();
            ResourceResponse targetResource = null;
            if (resourceService != null && targetResourceId != null) {
                try {
                    targetResource = resourceService.getResourceById(targetResourceId);
                } catch (Exception ignored) {}
            }

            if (targetResource == null && resourceService != null && incident.resourceId() != null) {
                try {
                    targetResource = resourceService.getResourceById(incident.resourceId());
                    if (targetResource != null) {
                        targetResourceId = targetResource.id();
                    }
                } catch (Exception ignored) {}
            }

            if (targetResource == null && resourceService != null) {
                throw new IllegalStateException("Cannot resolve canonical target resource " + targetResourceId + " for incident " + incidentId);
            }

            UUID finalTargetResourceId = targetResourceId != null ? targetResourceId : incident.resourceId();
            String finalTargetEnvironment = targetResource != null ? targetResource.environment() : "production";
            ResourceType finalTargetType = targetResource != null ? targetResource.type() : ResourceType.SERVICE;
            String finalTargetName = targetResource != null ? targetResource.name() : (finalTargetResourceId != null ? finalTargetResourceId.toString() : "unknown");

            String metric = primaryCandidate.candidateMetric() != null ? primaryCandidate.candidateMetric().toLowerCase() : "";

            List<RecoveryActionEntity> candidateActions = new ArrayList<>();

            // Deterministic action synthesis based on symptom/cause category
            if (metric.contains("conn") || metric.contains("pool") || finalTargetType == ResourceType.DATABASE) {
                candidateActions.add(buildCandidateAction("FLUSH_CACHE", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Clear cache to reduce downstream query pressure"));
                candidateActions.add(buildCandidateAction("RESTART_POOL", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Reset and replenish connection pool"));
            } else if (metric.contains("cpu") || metric.contains("thread")) {
                candidateActions.add(buildCandidateAction("SCALE_OUT", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Increase replica count to relieve CPU/thread saturation"));
                candidateActions.add(buildCandidateAction("RESTART_POD", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Gracefully restart worker pod to clear thread starvation"));
            } else if (metric.contains("mem") || metric.contains("heap") || metric.contains("leak")) {
                candidateActions.add(buildCandidateAction("RESTART_POD", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Restart container to reclaim leaked heap memory"));
                candidateActions.add(buildCandidateAction("ROLLBACK", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Rollback to prior stable version if memory exhaustion recurs"));
            } else if (metric.contains("error") || metric.contains("latency") || metric.contains("timeout")) {
                candidateActions.add(buildCandidateAction("CIRCUIT_BREAKER_ENABLE", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Trip circuit breaker to halt cascading failure propagation"));
                candidateActions.add(buildCandidateAction("RESTART_POD", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Restart degraded instance after shedding traffic"));
            } else {
                candidateActions.add(buildCandidateAction("RESTART_POD", finalTargetResourceId, finalTargetEnvironment, finalTargetType, finalTargetName, "Graceful restart of degraded component"));
            }

            Instant now = clock.instant();
            boolean hasCooldownSuppression = false;
            boolean hasPolicyBlock = false;
            List<String> cooldownReasons = new ArrayList<>();
            List<String> policyBlockReasons = new ArrayList<>();

            List<PolicyResponse> activePolicies = Collections.emptyList();
            if (policyService != null && finalTargetType != null) {
                try {
                    activePolicies = policyService.getActivePolicies(finalTargetType);
                } catch (Exception ex) {
                    log.warn("Failed to load active policies for resource type {}: {}", finalTargetType, ex.getMessage());
                }
            }

            for (RecoveryActionEntity cand : candidateActions) {
                String actType = cand.getActionType();

                // 1. Anti-flapping cooldown check (scoped to canonical target_resource_id)
                AntiFlappingEvaluation cooldownEval = cand.getTargetResourceId() != null
                        ? evaluateAntiFlapping(cand.getTargetResourceId(), actType, now)
                        : evaluateAntiFlapping(cand.getTarget(), actType, now);

                if (cooldownEval.inCooldown()) {
                    hasCooldownSuppression = true;
                    cooldownReasons.add(cooldownEval.suppressionReason());
                    actionsToSave.add(RecoveryActionEntity.builder()
                            .actionType("MANUAL_INVESTIGATION")
                            .targetResourceId(cand.getTargetResourceId())
                            .targetEnvironment(cand.getTargetEnvironment())
                            .targetResourceType(cand.getTargetResourceType())
                            .targetResourceName(cand.getTargetResourceName())
                            .target(cand.getTarget())
                            .status(RecoveryActionStatus.PENDING)
                            .result(cooldownEval.suppressionReason())
                            .build());
                    continue;
                }

                // 2. Pre-flight policy evaluation
                Double observedValue = resolveCandidateObservedValue(primaryCandidate, metric);
                PolicyPreFlightEvaluation policyEval = evaluatePolicyPreFlight(
                        activePolicies,
                        finalTargetType,
                        cand.getTargetResourceName(),
                        actType,
                        metric,
                        observedValue
                );

                if (!policyEval.allowed()) {
                    hasPolicyBlock = true;
                    policyBlockReasons.add(policyEval.blockReason());
                    actionsToSave.add(RecoveryActionEntity.builder()
                            .actionType("MANUAL_INVESTIGATION")
                            .targetResourceId(cand.getTargetResourceId())
                            .targetEnvironment(cand.getTargetEnvironment())
                            .targetResourceType(cand.getTargetResourceType())
                            .targetResourceName(cand.getTargetResourceName())
                            .target(cand.getTarget())
                            .status(RecoveryActionStatus.PENDING)
                            .result(policyEval.blockReason())
                            .build());
                    continue;
                }

                // Proposal permitted
                actionsToSave.add(cand);
            }

            // Deterministic operational risk evaluation
            if (finalTargetType == ResourceType.DATABASE) {
                risk = RecoveryRisk.HIGH;
            } else if (incident.severity() == IncidentSeverity.CRITICAL) {
                risk = RecoveryRisk.HIGH;
            } else if (finalTargetType == ResourceType.POD || finalTargetType == ResourceType.CONTAINER) {
                risk = RecoveryRisk.LOW;
            } else {
                risk = RecoveryRisk.MEDIUM;
            }

            // Escalate to CRITICAL if cooldown triggered or policy blocked
            if (hasCooldownSuppression || hasPolicyBlock) {
                risk = RecoveryRisk.CRITICAL;
            }

            // ADR-005 Safety Policy Enforcement:
            boolean isProduction = "production".equalsIgnoreCase(finalTargetEnvironment);
            approvalRequired = isProduction || risk == RecoveryRisk.HIGH || risk == RecoveryRisk.CRITICAL || hasCooldownSuppression || hasPolicyBlock;

            StringBuilder reasoningBuilder = new StringBuilder(String.format(
                    "Deterministic recovery plan synthesized from RCA analysis %s. " +
                            "Primary root cause: %s on %s (confidence: %s, score: %.2f). " +
                            "Operational risk evaluated as %s.",
                    rcaAnalysis.id(),
                    primaryCandidate.candidateCause(),
                    finalTargetName,
                    rcaAnalysis.confidenceLevel(),
                    confidence,
                    risk
            ));

            if (hasCooldownSuppression) {
                reasoningBuilder.append(" [ANTI-FLAPPING COOLDOWN: ")
                        .append(String.join("; ", cooldownReasons))
                        .append("]");
            }

            if (hasPolicyBlock) {
                reasoningBuilder.append(" [POLICY BLOCKED: ")
                        .append(String.join("; ", policyBlockReasons))
                        .append("]");
            }

            reasoning = reasoningBuilder.toString();
        }

        // 5. Persist plan and actions
        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .incidentId(incidentId)
                .reasoning(reasoning)
                .confidence(confidence)
                .risk(risk)
                .approvalRequired(approvalRequired)
                .build();

        RecoveryPlanEntity savedPlan = recoveryPlanRepository.save(plan);

        for (RecoveryActionEntity action : actionsToSave) {
            action.setRecoveryPlan(savedPlan);
            recoveryActionRepository.save(action);
        }

        List<RecoveryActionEntity> persistedActions = recoveryActionRepository.findByRecoveryPlanId(savedPlan.getId());
        log.info("Successfully created recovery plan {} with {} actions for incident {}",
                savedPlan.getId(), persistedActions.size(), incidentId);

        return mapToResponse(savedPlan, persistedActions);
    }

    private RecoveryActionEntity buildCandidateAction(
            String actionType,
            UUID targetResourceId,
            String targetEnvironment,
            ResourceType targetResourceType,
            String targetResourceName,
            String result) {
        return RecoveryActionEntity.builder()
                .actionType(actionType)
                .targetResourceId(targetResourceId)
                .targetEnvironment(targetEnvironment)
                .targetResourceType(targetResourceType)
                .targetResourceName(targetResourceName)
                .target(targetResourceName)
                .status(RecoveryActionStatus.PENDING)
                .result(result)
                .build();
    }

    @Override
    @Transactional
    public RecoveryActionResponse approveRecoveryAction(UUID incidentId, UUID actionId) {
        log.info("Approving recovery action {} for incident {}", actionId, incidentId);

        if (!incidentService.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident with ID '" + incidentId + "' not found");
        }

        RecoveryPlanEntity plan = recoveryPlanRepository.findByIncidentId(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery plan for incident ID '" + incidentId + "' not found"));

        RecoveryActionEntity action = recoveryActionRepository.findById(actionId)
                .orElseThrow(() -> new ResourceNotFoundException("Recovery action with ID '" + actionId + "' not found"));

        if (!action.getRecoveryPlan().getId().equals(plan.getId())) {
            throw new ResourceNotFoundException("Recovery action '" + actionId + "' does not belong to incident '" + incidentId + "'");
        }

        if (action.getStatus() == RecoveryActionStatus.APPROVED) {
            log.info("Recovery action {} is already APPROVED", actionId);
            return mapToActionResponse(action);
        }

        if (action.getStatus() != RecoveryActionStatus.PENDING) {
            throw new IllegalStateException("Cannot approve recovery action in status: " + action.getStatus());
        }

        // 1. Authenticated Operator Verification (S1 & S8)
        AuthenticatedOperator operator = SecurityUtils.getCurrentOperator()
                .orElseThrow(() -> new AccessDeniedException("Unauthenticated access denied: an authenticated operator principal is required for approval"));

        boolean isProduction = "production".equalsIgnoreCase(action.getTargetEnvironment());
        if (isProduction) {
            if (!operator.hasCapability(RecoveryCapability.RECOVERY_APPROVE_PRODUCTION) && !operator.isAdmin()) {
                throw new AccessDeniedException("Production recovery approval denied: operator lacks RECOVERY_APPROVE_PRODUCTION capability");
            }
        } else {
            if (!operator.canApprove()) {
                throw new AccessDeniedException("Recovery approval denied: operator lacks RECOVERY_APPROVE capability");
            }
        }

        // 2. Defense-in-depth safety guardrail: verify action is not in cooldown before approval (S5)
        AntiFlappingEvaluation cooldownEval = action.getTargetResourceId() != null
                ? evaluateAntiFlapping(action.getTargetResourceId(), action.getActionType(), clock.instant())
                : evaluateAntiFlapping(action.getTarget(), action.getActionType(), clock.instant());
        if (cooldownEval.inCooldown()) {
            throw new IllegalStateException("Cannot approve recovery action: target '" + action.getTarget() +
                    "' is in anti-flapping cooldown for action '" + action.getActionType() + "'");
        }

        // 3. Defense-in-depth safety guardrail: verify active policy allows this action before approval (S4)
        if (policyService != null && !"MANUAL_INVESTIGATION".equalsIgnoreCase(action.getActionType())) {
            try {
                ResourceType targetType = action.getTargetResourceType();
                String metricName = "*";
                Double observedValue = null;

                if (rcaAnalysisService != null) {
                    try {
                        RcaAnalysisResponse rca = rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId);
                        if (rca != null && rca.candidates() != null) {
                            RcaCandidateResponse cand = rca.candidates().stream()
                                    .filter(c -> Boolean.TRUE.equals(c.primaryCandidate()))
                                    .findFirst()
                                    .orElse(null);
                            if (cand != null) {
                                if (cand.candidateMetric() != null && !cand.candidateMetric().isBlank()) {
                                    metricName = cand.candidateMetric();
                                }
                                observedValue = resolveCandidateObservedValue(cand, metricName);
                                if (targetType == null && resourceService != null && cand.candidateResourceId() != null) {
                                    try {
                                        ResourceResponse cr = resourceService.getResourceById(cand.candidateResourceId());
                                        if (cr != null) {
                                            targetType = cr.type();
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (targetType == null && incidentService != null && resourceService != null) {
                    IncidentResponse incident = incidentService.getIncidentById(incidentId);
                    if (incident != null && incident.resourceId() != null) {
                        try {
                            ResourceResponse r = resourceService.getResourceById(incident.resourceId());
                            if (r != null) {
                                targetType = r.type();
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (targetType != null) {
                    String targetName = action.getTargetResourceName() != null ? action.getTargetResourceName() : action.getTarget();
                    PolicyPreFlightEvaluation policyEval = evaluatePolicyPreFlight(targetType, targetName, action.getActionType(), metricName, observedValue);
                    if (!policyEval.allowed()) {
                        throw new IllegalStateException("Cannot approve recovery action: " + policyEval.blockReason());
                    }
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception ignored) {}
        }

        // 4. Update action with auditable operator identity
        String capabilityUsed = isProduction
                ? (operator.hasCapability(RecoveryCapability.RECOVERY_APPROVE_PRODUCTION) ? RecoveryCapability.RECOVERY_APPROVE_PRODUCTION.name() : RecoveryCapability.RECOVERY_ADMIN.name())
                : (operator.hasCapability(RecoveryCapability.RECOVERY_APPROVE) ? RecoveryCapability.RECOVERY_APPROVE.name() : RecoveryCapability.RECOVERY_ADMIN.name());

        action.setApprovedByUserId(operator.getUserId());
        action.setApprovedByEmail(operator.getEmail());
        action.setApprovedByCapability(capabilityUsed);
        action.setApprovedAt(clock.instant());
        String reason = "Approved by human operator for execution (" + operator.getUserId() + ")";
        action.setApprovalReason(reason);
        action.setStatus(RecoveryActionStatus.APPROVED);
        action.setResult(reason);

        RecoveryActionEntity updated = recoveryActionRepository.save(action);

        // 5. Transactional Outbox Event in the SAME commit (S9)
        if (outboxEventRepository != null) {
            String payload = buildOutboxPayload(updated);
            RecoveryOutboxEventEntity outboxEvent = RecoveryOutboxEventEntity.builder()
                    .aggregateType("RecoveryAction")
                    .aggregateId(updated.getId())
                    .eventType("RECOVERY_ACTION_APPROVED")
                    .idempotencyKey("outbox-action-" + updated.getId() + "-v" + (updated.getVersion() != null ? updated.getVersion() : 0))
                    .payload(payload)
                    .status(OutboxEventStatus.PENDING)
                    .retryCount(0)
                    .createdAt(clock.instant())
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.info("Persisted transactional outbox event {} for recovery action {}", outboxEvent.getId(), updated.getId());
        }

        log.info("Recovery action {} successfully APPROVED by operator {}", actionId, operator.getUserId());
        return mapToActionResponse(updated);
    }

    private String buildOutboxPayload(RecoveryActionEntity action) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("actionId", action.getId().toString());
            map.put("planId", action.getRecoveryPlan() != null ? action.getRecoveryPlan().getId().toString() : null);
            map.put("actionType", action.getActionType());
            map.put("targetResourceId", action.getTargetResourceId() != null ? action.getTargetResourceId().toString() : null);
            map.put("targetEnvironment", action.getTargetEnvironment());
            map.put("targetResourceType", action.getTargetResourceType() != null ? action.getTargetResourceType().name() : null);
            map.put("targetResourceName", action.getTargetResourceName());
            map.put("approvedByUserId", action.getApprovedByUserId());
            map.put("approvedAt", action.getApprovedAt() != null ? action.getApprovedAt().toString() : null);
            map.put("timestamp", clock.instant().toString());
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event payload for action {}", action.getId(), e);
            return "{\"actionId\":\"" + action.getId() + "\"}";
        }
    }

    @Override
    @Transactional
    public RecoveryPlanResponse createRecoveryPlan(CreateRecoveryPlanRequest request) {
        log.info("Creating manual recovery plan for incident ID {}", request.incidentId());

        if (!incidentService.existsById(request.incidentId())) {
            throw new ResourceNotFoundException("Incident with ID '" + request.incidentId() + "' not found");
        }

        IncidentResponse incident = null;
        if (incidentService != null) {
            try {
                incident = incidentService.getIncidentById(request.incidentId());
            } catch (Exception ignored) {}
        }

        RecoveryPlanEntity plan = RecoveryPlanEntity.builder()
                .incidentId(request.incidentId())
                .reasoning(request.reasoning())
                .confidence(request.confidence())
                .risk(request.risk())
                .approvalRequired(request.approvalRequired() != null ? request.approvalRequired() : true)
                .build();

        RecoveryPlanEntity savedPlan = recoveryPlanRepository.save(plan);

        if (request.actions() != null && !request.actions().isEmpty()) {
            for (CreateRecoveryActionRequest actReq : request.actions()) {
                ResourceResponse resolvedRes = resolveTargetResource(actReq.target(), incident);

                UUID targetResId = resolvedRes != null ? resolvedRes.id() : (incident != null && incident.resourceId() != null ? incident.resourceId() : UUID.randomUUID());
                String targetEnv = resolvedRes != null ? resolvedRes.environment() : "production";
                ResourceType targetType = resolvedRes != null ? resolvedRes.type() : ResourceType.SERVICE;
                String targetName = resolvedRes != null ? resolvedRes.name() : (actReq.target() != null ? actReq.target() : "unknown");

                RecoveryActionEntity actEntity = RecoveryActionEntity.builder()
                        .recoveryPlan(savedPlan)
                        .actionType(actReq.actionType())
                        .targetResourceId(targetResId)
                        .targetEnvironment(targetEnv)
                        .targetResourceType(targetType)
                        .targetResourceName(targetName)
                        .target(targetName)
                        .status(actReq.status() != null ? actReq.status() : RecoveryActionStatus.PENDING)
                        .result(actReq.result())
                        .build();
                recoveryActionRepository.save(actEntity);
            }
        }

        List<RecoveryActionEntity> savedActions = recoveryActionRepository.findByRecoveryPlanId(savedPlan.getId());
        return mapToResponse(savedPlan, savedActions);
    }

    @Override
    @Transactional
    public RecoveryPlanResponse createRecoveryPlan(UUID incidentId,
                                                   String reasoning,
                                                   Double confidence,
                                                   RecoveryRisk risk,
                                                   Boolean approvalRequired,
                                                   List<RecoveryActionEntity> actions) {
        log.info("Creating recovery plan (legacy entity overload) for incident ID {}", incidentId);

        if (!incidentService.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident with ID '" + incidentId + "' not found");
        }

        IncidentResponse incident = null;
        if (incidentService != null) {
            try {
                incident = incidentService.getIncidentById(incidentId);
            } catch (Exception ignored) {}
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
                if (action.getTargetResourceId() == null) {
                    ResourceResponse resolvedRes = resolveTargetResource(action.getTarget(), incident);
                    if (resolvedRes != null) {
                        action.setTargetResourceId(resolvedRes.id());
                        action.setTargetEnvironment(resolvedRes.environment());
                        action.setTargetResourceType(resolvedRes.type());
                        action.setTargetResourceName(resolvedRes.name());
                        if (action.getTarget() == null) {
                            action.setTarget(resolvedRes.name());
                        }
                    } else if (incident != null && incident.resourceId() != null) {
                        action.setTargetResourceId(incident.resourceId());
                        action.setTargetEnvironment("production");
                        action.setTargetResourceType(ResourceType.SERVICE);
                        action.setTargetResourceName(action.getTarget() != null ? action.getTarget() : "unknown");
                    } else {
                        action.setTargetResourceId(UUID.randomUUID());
                        action.setTargetEnvironment("production");
                        action.setTargetResourceType(ResourceType.SERVICE);
                        action.setTargetResourceName(action.getTarget() != null ? action.getTarget() : "unknown");
                    }
                }
                recoveryActionRepository.save(action);
            }
        }

        List<RecoveryActionEntity> savedActions = recoveryActionRepository.findByRecoveryPlanId(savedPlan.getId());
        return mapToResponse(savedPlan, savedActions);
    }

    private ResourceResponse resolveTargetResource(String targetIdentifier, IncidentResponse incident) {
        if (resourceService == null) {
            return null;
        }
        if (targetIdentifier != null) {
            try {
                UUID parsedUuid = UUID.fromString(targetIdentifier);
                return resourceService.getResourceById(parsedUuid);
            } catch (Exception ignored) {}

            try {
                List<ResourceResponse> all = resourceService.getAllResources(null, null, null);
                if (all != null) {
                    for (ResourceResponse r : all) {
                        if (targetIdentifier.equalsIgnoreCase(r.name())) {
                            return r;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (incident != null && incident.resourceId() != null) {
            try {
                return resourceService.getResourceById(incident.resourceId());
            } catch (Exception ignored) {}
        }
        return null;
    }

    private RecoveryPlanResponse mapToResponse(RecoveryPlanEntity plan, List<RecoveryActionEntity> actions) {
        List<RecoveryActionResponse> actionResponses = actions.stream()
                .map(this::mapToActionResponse)
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

    private RecoveryActionResponse mapToActionResponse(RecoveryActionEntity a) {
        return new RecoveryActionResponse(
                a.getId(),
                a.getRecoveryPlan().getId(),
                a.getActionType(),
                a.getTarget(),
                a.getStatus(),
                a.getResult(),
                a.getStartedAt(),
                a.getCompletedAt(),
                a.getTargetResourceId(),
                a.getTargetEnvironment(),
                a.getTargetResourceType(),
                a.getTargetResourceName(),
                a.getApprovedByUserId(),
                a.getApprovedByEmail(),
                a.getApprovedByCapability(),
                a.getApprovedAt(),
                a.getApprovalReason()
        );
    }

    public AntiFlappingEvaluation evaluateAntiFlapping(UUID targetResourceId, String actionType, Instant now) {
        if (recoveryActionRepository == null || targetResourceId == null || actionType == null) {
            return new AntiFlappingEvaluation(false, 0, null);
        }

        List<RecoveryActionEntity> history = recoveryActionRepository.findByTargetResourceIdAndActionType(targetResourceId, actionType);
        if (history == null || history.isEmpty()) {
            return new AntiFlappingEvaluation(false, 0, null);
        }

        return evaluateAntiFlappingFromHistory(history, targetResourceId.toString(), actionType, now);
    }

    public AntiFlappingEvaluation evaluateAntiFlapping(String target, String actionType, Instant now) {
        if (recoveryActionRepository == null || target == null || actionType == null) {
            return new AntiFlappingEvaluation(false, 0, null);
        }

        List<RecoveryActionEntity> history = recoveryActionRepository.findByTargetAndActionType(target, actionType);
        if (history == null || history.isEmpty()) {
            return new AntiFlappingEvaluation(false, 0, null);
        }

        return evaluateAntiFlappingFromHistory(history, target, actionType, now);
    }

    private AntiFlappingEvaluation evaluateAntiFlappingFromHistory(List<RecoveryActionEntity> history, String targetLabel, String actionType, Instant now) {
        Instant windowStart = now.minus(Duration.ofHours(1));

        // Find the latest successful recovery within the window, if any
        Instant latestSuccess = history.stream()
                .filter(a -> a.getStatus() == RecoveryActionStatus.SUCCESS || a.getStatus() == RecoveryActionStatus.COMPLETED)
                .map(this::resolveEffectiveTimestamp)
                .filter(ts -> !ts.isBefore(windowStart) && !ts.isAfter(now))
                .max(Instant::compareTo)
                .orElse(null);

        // Count failed recovery actions within [windowStart, now] that occurred strictly after latestSuccess
        long failureCount = history.stream()
                .filter(a -> a.getStatus() == RecoveryActionStatus.FAILED)
                .filter(a -> {
                    Instant ts = resolveEffectiveTimestamp(a);
                    if (ts.isBefore(windowStart) || ts.isAfter(now)) {
                        return false;
                    }
                    if (latestSuccess != null && (ts.isBefore(latestSuccess) || ts.equals(latestSuccess))) {
                        return false;
                    }
                    return true;
                })
                .count();

        if (failureCount >= 2) {
            String reason = String.format(
                    "Suppressed by anti-flapping cooldown: %d failed recovery attempts recorded within the last 1 hour on target '%s' for action '%s'. Escalated to human SRE investigation.",
                    failureCount, targetLabel, actionType
            );
            return new AntiFlappingEvaluation(true, failureCount, reason);
        }

        return new AntiFlappingEvaluation(false, failureCount, null);
    }

    private Instant resolveEffectiveTimestamp(RecoveryActionEntity action) {
        if (action.getCompletedAt() != null) {
            return action.getCompletedAt();
        }
        if (action.getStartedAt() != null) {
            return action.getStartedAt();
        }
        if (action.getRecoveryPlan() != null && action.getRecoveryPlan().getCreatedAt() != null) {
            return action.getRecoveryPlan().getCreatedAt();
        }
        return Instant.EPOCH;
    }

    public PolicyPreFlightEvaluation evaluatePolicyPreFlight(
            ResourceType targetType,
            String targetName,
            String proposedActionType,
            String candidateMetric,
            Double observedValue) {

        if (policyService == null || targetType == null) {
            return new PolicyPreFlightEvaluation(true, null, null);
        }

        List<PolicyResponse> activePolicies;
        try {
            activePolicies = policyService.getActivePolicies(targetType);
        } catch (Exception ex) {
            log.warn("Failed to load active policies for resource type {}: {}", targetType, ex.getMessage());
            return new PolicyPreFlightEvaluation(true, null, null);
        }

        return evaluatePolicyPreFlight(activePolicies, targetType, targetName, proposedActionType, candidateMetric, observedValue);
    }

    public PolicyPreFlightEvaluation evaluatePolicyPreFlight(
            List<PolicyResponse> activePolicies,
            ResourceType targetType,
            String targetName,
            String proposedActionType,
            String candidateMetric,
            Double observedValue) {

        if (activePolicies == null || activePolicies.isEmpty() || targetType == null) {
            return new PolicyPreFlightEvaluation(true, null, null);
        }

        for (PolicyResponse policy : activePolicies) {
            if (policy.enabled() != null && !policy.enabled()) {
                continue; // Disabled policy ignored
            }

            if (!isMetricApplicable(policy.metricName(), candidateMetric)) {
                continue; // Policy does not apply to this metric
            }

            String polAction = policy.action() != null ? policy.action().trim().toUpperCase() : "";
            String proposedUpper = proposedActionType != null ? proposedActionType.trim().toUpperCase() : "";

            // 1. Explicit Prohibition
            boolean isExplicitProhibition = polAction.equals("PROHIBIT_" + proposedUpper)
                    || polAction.equals("DENY_" + proposedUpper)
                    || polAction.equals("BLOCK_" + proposedUpper)
                    || polAction.equals("NO_" + proposedUpper)
                    || polAction.equals("MANUAL_ONLY")
                    || polAction.equals("BLOCKED")
                    || polAction.equals("DENIED")
                    || polAction.equals("PROHIBIT");

            if (isExplicitProhibition) {
                if (policy.threshold() != null) {
                    if (observedValue != null && observedValue >= policy.threshold()) {
                        String reason = String.format(
                                "Blocked by policy '%s': action '%s' prohibited when %s >= %.2f (observed: %.2f)",
                                policy.name(), proposedActionType, policy.metricName(), policy.threshold(), observedValue
                        );
                        return new PolicyPreFlightEvaluation(false, policy, reason);
                    }
                    continue; // Threshold not met or not observable; conditional threshold policy does not trigger
                }

                String reason = String.format(
                        "Blocked by policy '%s': action '%s' is prohibited for resource type %s on metric '%s'",
                        policy.name(), proposedActionType, targetType, policy.metricName()
                );
                return new PolicyPreFlightEvaluation(false, policy, reason);
            }

            // 2. Safe Threshold Guardrail on allowed action
            if (polAction.equals(proposedUpper) || polAction.equals("ALLOW_" + proposedUpper)) {
                if (policy.threshold() != null && observedValue != null && observedValue > policy.threshold()) {
                    String reason = String.format(
                            "Blocked by policy '%s': observed metric %.2f exceeds maximum permitted threshold %.2f for action '%s'",
                            policy.name(), observedValue, policy.threshold(), proposedActionType
                    );
                    return new PolicyPreFlightEvaluation(false, policy, reason);
                }
                return new PolicyPreFlightEvaluation(true, policy, null);
            }
        }

        return new PolicyPreFlightEvaluation(true, null, null);
    }

    private Double resolveCandidateObservedValue(RcaCandidateResponse candidate, String metricName) {
        if (candidate == null) {
            return null;
        }
        if (candidate.evidence() != null && !candidate.evidence().isEmpty()) {
            for (RcaEvidenceResponse ev : candidate.evidence()) {
                if (ev.observedValue() != null) {
                    if (metricName == null || isMetricApplicable(metricName, ev.metricName())) {
                        return ev.observedValue();
                    }
                }
            }
            for (RcaEvidenceResponse ev : candidate.evidence()) {
                if (ev.observedValue() != null) {
                    return ev.observedValue();
                }
            }
        }
        return candidate.evidenceScore();
    }

    private boolean isMetricApplicable(String policyMetric, String candidateMetric) {
        if (policyMetric == null || candidateMetric == null) {
            return false;
        }
        String p = policyMetric.trim().toLowerCase();
        String c = candidateMetric.trim().toLowerCase();
        if (p.equals("*") || p.equals("all") || c.equals("*") || c.equals("all")) {
            return true;
        }
        if (p.isEmpty() || c.isEmpty()) {
            return false;
        }
        return p.equals(c) || c.contains(p) || p.contains(c);
    }
}
