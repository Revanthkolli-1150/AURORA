package com.aurora.platform.recovery.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.service.IncidentService;
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
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.RecoveryPlanEntity;
import com.aurora.platform.recovery.entity.RecoveryRisk;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.RecoveryPlanRepository;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
            @Autowired(required = false) PolicyService policyService) {
        this.recoveryPlanRepository = recoveryPlanRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.incidentService = incidentService;
        this.rcaAnalysisService = rcaAnalysisService;
        this.resourceService = resourceService;
        this.policyService = policyService;
    }

    public RecoveryServiceImpl(
            RecoveryPlanRepository recoveryPlanRepository,
            RecoveryActionRepository recoveryActionRepository,
            IncidentService incidentService) {
        this(recoveryPlanRepository, recoveryActionRepository, incidentService, null, null, null);
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

            String targetName = incident.resourceId().toString();
            if (resourceService != null) {
                try {
                    ResourceResponse res = resourceService.getResourceById(incident.resourceId());
                    targetName = res.name();
                } catch (Exception ignored) {}
            }

            actionsToSave.add(RecoveryActionEntity.builder()
                    .actionType("MANUAL_INVESTIGATION")
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

            String targetName = targetResource != null ? targetResource.name() : (targetResourceId != null ? targetResourceId.toString() : incident.resourceId().toString());
            String metric = primaryCandidate.candidateMetric() != null ? primaryCandidate.candidateMetric().toLowerCase() : "";
            ResourceType targetType = targetResource != null ? targetResource.type() : null;
            if (targetType == null && resourceService != null && incident.resourceId() != null) {
                try {
                    ResourceResponse incRes = resourceService.getResourceById(incident.resourceId());
                    if (incRes != null) {
                        targetType = incRes.type();
                        if (targetResource == null) {
                            targetResource = incRes;
                        }
                    }
                } catch (Exception ignored) {}
            }

            List<RecoveryActionEntity> candidateActions = new ArrayList<>();

            // Deterministic action synthesis based on symptom/cause category
            if (metric.contains("conn") || metric.contains("pool") || targetType == ResourceType.DATABASE) {
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("FLUSH_CACHE")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Clear cache to reduce downstream query pressure")
                        .build());
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("RESTART_POOL")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Reset and replenish connection pool")
                        .build());
            } else if (metric.contains("cpu") || metric.contains("thread")) {
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("SCALE_OUT")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Increase replica count to relieve CPU/thread saturation")
                        .build());
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("RESTART_POD")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Gracefully restart worker pod to clear thread starvation")
                        .build());
            } else if (metric.contains("mem") || metric.contains("heap") || metric.contains("leak")) {
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("RESTART_POD")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Restart container to reclaim leaked heap memory")
                        .build());
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("ROLLBACK")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Rollback to prior stable version if memory exhaustion recurs")
                        .build());
            } else if (metric.contains("error") || metric.contains("latency") || metric.contains("timeout")) {
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("CIRCUIT_BREAKER_ENABLE")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Trip circuit breaker to halt cascading failure propagation")
                        .build());
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("RESTART_POD")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Restart degraded instance after shedding traffic")
                        .build());
            } else {
                candidateActions.add(RecoveryActionEntity.builder()
                        .actionType("RESTART_POD")
                        .target(targetName)
                        .status(RecoveryActionStatus.PENDING)
                        .result("Graceful restart of degraded component")
                        .build());
            }

            Instant now = clock.instant();
            boolean hasCooldownSuppression = false;
            boolean hasPolicyBlock = false;
            List<String> cooldownReasons = new ArrayList<>();
            List<String> policyBlockReasons = new ArrayList<>();

            List<PolicyResponse> activePolicies = Collections.emptyList();
            if (policyService != null && targetType != null) {
                try {
                    activePolicies = policyService.getActivePolicies(targetType);
                } catch (Exception ex) {
                    log.warn("Failed to load active policies for resource type {}: {}", targetType, ex.getMessage());
                }
            }

            for (RecoveryActionEntity cand : candidateActions) {
                String actType = cand.getActionType();

                // 1. Anti-flapping cooldown check (Part B)
                AntiFlappingEvaluation cooldownEval = evaluateAntiFlapping(cand.getTarget(), actType, now);
                if (cooldownEval.inCooldown()) {
                    hasCooldownSuppression = true;
                    cooldownReasons.add(cooldownEval.suppressionReason());
                    actionsToSave.add(RecoveryActionEntity.builder()
                            .actionType("MANUAL_INVESTIGATION")
                            .target(cand.getTarget())
                            .status(RecoveryActionStatus.PENDING)
                            .result(cooldownEval.suppressionReason())
                            .build());
                    continue;
                }

                // 2. Pre-flight policy evaluation (Part A)
                Double observedValue = resolveCandidateObservedValue(primaryCandidate, metric);
                PolicyPreFlightEvaluation policyEval = evaluatePolicyPreFlight(
                        activePolicies,
                        targetType,
                        cand.getTarget(),
                        actType,
                        metric,
                        observedValue
                );

                if (!policyEval.allowed()) {
                    hasPolicyBlock = true;
                    policyBlockReasons.add(policyEval.blockReason());
                    actionsToSave.add(RecoveryActionEntity.builder()
                            .actionType("MANUAL_INVESTIGATION")
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
            if (targetType == ResourceType.DATABASE) {
                risk = RecoveryRisk.HIGH;
            } else if (incident.severity() == IncidentSeverity.CRITICAL) {
                risk = RecoveryRisk.HIGH;
            } else if (targetType == ResourceType.POD || targetType == ResourceType.CONTAINER) {
                risk = RecoveryRisk.LOW;
            } else {
                risk = RecoveryRisk.MEDIUM;
            }

            // Escalate to CRITICAL if cooldown triggered or policy blocked
            if (hasCooldownSuppression || hasPolicyBlock) {
                risk = RecoveryRisk.CRITICAL;
            }

            // ADR-005 Safety Policy Enforcement:
            // Production environments, High/Critical risk actions, Cooldown, or Policy blocks strictly require human approval
            boolean isProduction = targetResource != null && "production".equalsIgnoreCase(targetResource.environment());
            approvalRequired = isProduction || risk == RecoveryRisk.HIGH || risk == RecoveryRisk.CRITICAL || hasCooldownSuppression || hasPolicyBlock;

            StringBuilder reasoningBuilder = new StringBuilder(String.format(
                    "Deterministic recovery plan synthesized from RCA analysis %s. " +
                            "Primary root cause: %s on %s (confidence: %s, score: %.2f). " +
                            "Operational risk evaluated as %s.",
                    rcaAnalysis.id(),
                    primaryCandidate.candidateCause(),
                    targetName,
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

        // Defense-in-depth safety guardrail: verify action is not in cooldown before approval
        AntiFlappingEvaluation cooldownEval = evaluateAntiFlapping(action.getTarget(), action.getActionType(), clock.instant());
        if (cooldownEval.inCooldown()) {
            throw new IllegalStateException("Cannot approve recovery action: target '" + action.getTarget() +
                    "' is in anti-flapping cooldown for action '" + action.getActionType() + "'");
        }

        // Defense-in-depth safety guardrail: verify active policy allows this action before approval
        if (policyService != null && !"MANUAL_INVESTIGATION".equalsIgnoreCase(action.getActionType())) {
            try {
                ResourceType targetType = null;
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
                                if (resourceService != null && cand.candidateResourceId() != null) {
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
                    PolicyPreFlightEvaluation policyEval = evaluatePolicyPreFlight(targetType, action.getTarget(), action.getActionType(), metricName, observedValue);
                    if (!policyEval.allowed()) {
                        throw new IllegalStateException("Cannot approve recovery action: " + policyEval.blockReason());
                    }
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception ignored) {}
        }

        action.setStatus(RecoveryActionStatus.APPROVED);
        action.setResult("Approved by human operator for execution");
        RecoveryActionEntity updated = recoveryActionRepository.save(action);

        log.info("Recovery action {} successfully APPROVED", actionId);
        return mapToActionResponse(updated);
    }


    @Override
    @Transactional
    public RecoveryPlanResponse createRecoveryPlan(CreateRecoveryPlanRequest request) {
        log.info("Creating manual recovery plan for incident ID {}", request.incidentId());

        if (!incidentService.existsById(request.incidentId())) {
            throw new ResourceNotFoundException("Incident with ID '" + request.incidentId() + "' not found");
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
                RecoveryActionEntity actEntity = RecoveryActionEntity.builder()
                        .recoveryPlan(savedPlan)
                        .actionType(actReq.actionType())
                        .target(actReq.target())
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
                a.getCompletedAt()
        );
    }

    public AntiFlappingEvaluation evaluateAntiFlapping(String target, String actionType, Instant now) {
        if (recoveryActionRepository == null || target == null || actionType == null) {
            return new AntiFlappingEvaluation(false, 0, null);
        }

        List<RecoveryActionEntity> history = recoveryActionRepository.findByTargetAndActionType(target, actionType);
        if (history == null || history.isEmpty()) {
            return new AntiFlappingEvaluation(false, 0, null);
        }

        Instant windowStart = now.minus(Duration.ofHours(1));

        // Find the latest successful recovery within the window, if any
        Instant latestSuccess = history.stream()
                .filter(a -> a.getStatus() == RecoveryActionStatus.SUCCESS)
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
                    failureCount, target, actionType
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
