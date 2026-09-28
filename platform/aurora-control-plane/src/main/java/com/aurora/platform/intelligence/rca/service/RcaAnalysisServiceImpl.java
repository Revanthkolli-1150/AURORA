package com.aurora.platform.intelligence.rca.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.intelligence.anomaly.service.AnomalyDetectionService;
import com.aurora.platform.intelligence.rca.config.RcaProperties;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.dto.RcaEvidenceResponse;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.entity.RcaCandidateEntity;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceEntity;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceType;
import com.aurora.platform.intelligence.rca.repository.RcaAnalysisRepository;
import com.aurora.platform.intelligence.rca.repository.RcaCandidateRepository;
import com.aurora.platform.intelligence.rca.repository.RcaEvidenceRepository;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.resource.repository.ResourceRepository;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import com.aurora.platform.intelligence.rca.application.DependencyTopologyQuery;
import com.aurora.platform.intelligence.rca.application.EdgeWeightProvider;
import com.aurora.platform.intelligence.rca.application.EdgeWeightResult;
import com.aurora.platform.intelligence.rca.application.IncidentEvidenceQuery;
import com.aurora.platform.intelligence.rca.application.ResourceQuery;
import com.aurora.platform.intelligence.rca.application.TelemetryHistoryQuery;
import com.aurora.platform.intelligence.rca.infrastructure.DependencyTopologyQueryAdapter;
import com.aurora.platform.intelligence.rca.infrastructure.IncidentEvidenceQueryAdapter;
import com.aurora.platform.intelligence.rca.infrastructure.ResourceQueryAdapter;
import com.aurora.platform.intelligence.rca.infrastructure.TelemetryHistoryQueryAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RcaAnalysisServiceImpl implements RcaAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(RcaAnalysisServiceImpl.class);

    // Deterministic Scoring Weights
    public static final double WEIGHT_ANOMALY = 0.40;
    public static final double WEIGHT_TEMPORAL_PRECEDENCE = 0.25;
    public static final double WEIGHT_DEPENDENCY = 0.20;
    public static final double WEIGHT_TELEMETRY_CORRELATION = 0.15;
    public static final double MIN_MEANINGFUL_SCORE = 0.30;

    private final IncidentEvidenceQuery incidentEvidenceQuery;
    private final ResourceQuery resourceQuery;
    private final DependencyTopologyQuery dependencyTopologyQuery;
    private final TelemetryHistoryQuery telemetryHistoryQuery;
    private final AnomalyDetectionService anomalyDetectionService;
    private final RcaAnalysisRepository analysisRepository;
    private final RcaCandidateRepository candidateRepository;
    private final RcaEvidenceRepository evidenceRepository;
    private final RcaProperties rcaProperties;
    private final EdgeWeightProvider edgeWeightProvider;

    @Autowired
    public RcaAnalysisServiceImpl(
            IncidentEvidenceQuery incidentEvidenceQuery,
            ResourceQuery resourceQuery,
            DependencyTopologyQuery dependencyTopologyQuery,
            TelemetryHistoryQuery telemetryHistoryQuery,
            AnomalyDetectionService anomalyDetectionService,
            RcaAnalysisRepository analysisRepository,
            RcaCandidateRepository candidateRepository,
            RcaEvidenceRepository evidenceRepository,
            RcaProperties rcaProperties,
            @Autowired(required = false) EdgeWeightProvider edgeWeightProvider) {
        this.incidentEvidenceQuery = incidentEvidenceQuery;
        this.resourceQuery = resourceQuery;
        this.dependencyTopologyQuery = dependencyTopologyQuery;
        this.telemetryHistoryQuery = telemetryHistoryQuery;
        this.anomalyDetectionService = anomalyDetectionService;
        this.analysisRepository = analysisRepository;
        this.candidateRepository = candidateRepository;
        this.evidenceRepository = evidenceRepository;
        this.rcaProperties = rcaProperties;
        this.edgeWeightProvider = edgeWeightProvider;
    }

    public RcaAnalysisServiceImpl(
            IncidentEvidenceQuery incidentEvidenceQuery,
            ResourceQuery resourceQuery,
            DependencyTopologyQuery dependencyTopologyQuery,
            TelemetryHistoryQuery telemetryHistoryQuery,
            AnomalyDetectionService anomalyDetectionService,
            RcaAnalysisRepository analysisRepository,
            RcaCandidateRepository candidateRepository,
            RcaEvidenceRepository evidenceRepository,
            RcaProperties rcaProperties) {
        this(
                incidentEvidenceQuery,
                resourceQuery,
                dependencyTopologyQuery,
                telemetryHistoryQuery,
                anomalyDetectionService,
                analysisRepository,
                candidateRepository,
                evidenceRepository,
                rcaProperties,
                null
        );
    }

    public RcaAnalysisServiceImpl(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository incidentEvidenceRepository,
            ResourceRepository resourceRepository,
            ResourceDependencyRepository dependencyRepository,
            TelemetryEventRepository telemetryEventRepository,
            AnomalyDetectionService anomalyDetectionService,
            RcaAnalysisRepository analysisRepository,
            RcaCandidateRepository candidateRepository,
            RcaEvidenceRepository evidenceRepository,
            RcaProperties rcaProperties) {
        this(
                new IncidentEvidenceQueryAdapter(incidentRepository, incidentEvidenceRepository),
                new ResourceQueryAdapter(resourceRepository),
                new DependencyTopologyQueryAdapter(dependencyRepository),
                new TelemetryHistoryQueryAdapter(telemetryEventRepository),
                anomalyDetectionService,
                analysisRepository,
                candidateRepository,
                evidenceRepository,
                rcaProperties,
                null
        );
    }

    public RcaAnalysisServiceImpl(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository incidentEvidenceRepository,
            ResourceRepository resourceRepository,
            ResourceDependencyRepository dependencyRepository,
            TelemetryEventRepository telemetryEventRepository,
            AnomalyDetectionService anomalyDetectionService,
            RcaAnalysisRepository analysisRepository,
            RcaCandidateRepository candidateRepository,
            RcaEvidenceRepository evidenceRepository,
            RcaProperties rcaProperties,
            EdgeWeightProvider edgeWeightProvider) {
        this(
                new IncidentEvidenceQueryAdapter(incidentRepository, incidentEvidenceRepository),
                new ResourceQueryAdapter(resourceRepository),
                new DependencyTopologyQueryAdapter(dependencyRepository),
                new TelemetryHistoryQueryAdapter(telemetryEventRepository),
                anomalyDetectionService,
                analysisRepository,
                candidateRepository,
                evidenceRepository,
                rcaProperties,
                edgeWeightProvider
        );
    }

    @Override
    @Transactional
    public RcaAnalysisResponse analyzeIncident(UUID incidentId) {
        Instant startedAt = Instant.now();
        log.info("Starting deterministic RCA analysis for incident {}", incidentId);

        // 1. Validate Incident Exists
        IncidentEntity incident = incidentEvidenceQuery.findIncident(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found with ID: " + incidentId));

        UUID investigatedResourceId = incident.getResourceId();
        ResourceEntity investigatedResource = resourceQuery.findResource(investigatedResourceId).orElse(null);
        String investigatedResourceName = investigatedResource != null ? investigatedResource.getName() : investigatedResourceId.toString();

        // 2. Incident Anomaly Evidence and Investigation Window
        List<IncidentAnomalyEvidenceEntity> incidentEvidenceList = incidentEvidenceQuery
                .findEvidenceForIncident(incidentId);

        Instant incidentRefTime = !incidentEvidenceList.isEmpty()
                ? incidentEvidenceList.get(0).getObservedAt()
                : (incident.getDetectedAt() != null ? incident.getDetectedAt() : startedAt);

        Instant windowEnd = incident.getDetectedAt() != null ? incident.getDetectedAt() : startedAt;
        if (!incidentEvidenceList.isEmpty()) {
            Instant latestEvidence = incidentEvidenceList.get(incidentEvidenceList.size() - 1).getObservedAt();
            if (latestEvidence.isAfter(windowEnd)) {
                windowEnd = latestEvidence;
            }
        }
        Instant windowStart = windowEnd.minus(Duration.ofMinutes(rcaProperties.getLookbackMinutes()));

        // 3. Inspect Direct Dependency Relationships (1-Hop Radius Only)
        List<UUID> directDependencyList = dependencyTopologyQuery.findDirectDependencies(investigatedResourceId);
        Set<UUID> directDependencyIds = new LinkedHashSet<>(directDependencyList);

        List<UUID> directDependentList = dependencyTopologyQuery.findDirectDependents(investigatedResourceId);
        Set<UUID> directDependentIds = new LinkedHashSet<>(directDependentList);

        // 4. Candidate Resources to Investigate (1-Hop Topological Evaluation)
        Set<UUID> candidateResourceIds = new LinkedHashSet<>();
        // Priority order: direct dependencies, incident resource itself, direct dependents
        candidateResourceIds.addAll(directDependencyIds);
        candidateResourceIds.add(investigatedResourceId);
        candidateResourceIds.addAll(directDependentIds);

        List<CandidateEvaluation> evaluations = new ArrayList<>();

        for (UUID candidateId : candidateResourceIds) {
            ResourceEntity candidateResource = resourceQuery.findResource(candidateId).orElse(null);
            String candidateName = candidateResource != null ? candidateResource.getName() : candidateId.toString();

            CandidateEvaluation eval = evaluateCandidate(
                    candidateId,
                    candidateName,
                    investigatedResourceId,
                    investigatedResourceName,
                    directDependencyIds.contains(candidateId),
                    directDependentIds.contains(candidateId),
                    incidentRefTime,
                    windowStart,
                    windowEnd,
                    incidentEvidenceList,
                    incident.getDetectedAt() != null ? incident.getDetectedAt() : startedAt,
                    incidentId
            );

            if (eval != null && eval.hasEvidence()) {
                evaluations.add(eval);
            }
        }

        // 5. Deterministic Ranking
        // Sort by: evidenceScore descending, temporal delta ascending (closer anomaly first), candidateResourceId ascending
        evaluations.sort(Comparator
                .comparing(CandidateEvaluation::getEvidenceScore, Comparator.reverseOrder())
                .thenComparing(CandidateEvaluation::getPrecedenceSeconds, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(eval -> eval.getCandidateResourceId().toString()));

        // Assign ranks and primary candidate flag
        double highestScore = evaluations.isEmpty() ? 0.0 : evaluations.get(0).getEvidenceScore();
        boolean sufficientEvidence = !evaluations.isEmpty() && highestScore >= MIN_MEANINGFUL_SCORE && hasAnomalousEvidence(evaluations.get(0));

        RcaAnalysisStatus status = sufficientEvidence
                ? RcaAnalysisStatus.COMPLETED
                : RcaAnalysisStatus.INSUFFICIENT_EVIDENCE;

        double confidence = roundScore(highestScore);
        String confidenceLvl = mapConfidenceLevel(confidence);

        String summary;
        if (sufficientEvidence) {
            CandidateEvaluation rank1 = evaluations.get(0);
            summary = String.format("RCA analysis completed. Primary candidate root cause: %s on %s (evidence score: %.2f, confidence: %s).",
                    rank1.getCandidateCause(), rank1.getCandidateName(), rank1.getEvidenceScore(), confidenceLvl);
        } else {
            summary = "No sufficiently supported root-cause candidate was identified from the available telemetry, anomaly evidence, and dependency relationships.";
        }

        Instant completedAt = Instant.now();

        // 6. Persist Analysis
        RcaAnalysisEntity analysisEntity = RcaAnalysisEntity.builder()
                .incidentId(incidentId)
                .status(status)
                .investigatedResourceId(investigatedResourceId)
                .summary(summary)
                .confidence(confidence)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .createdAt(completedAt)
                .build();
        analysisEntity = analysisRepository.save(analysisEntity);

        // 7. Persist Candidates and Evidence
        List<RcaCandidateResponse> candidateResponses = new ArrayList<>();
        int currentRank = 1;

        for (CandidateEvaluation eval : evaluations) {
            boolean isPrimary = sufficientEvidence && currentRank == 1;

            RcaCandidateEntity candidateEntity = RcaCandidateEntity.builder()
                    .analysisId(analysisEntity.getId())
                    .candidateResourceId(eval.getCandidateResourceId())
                    .candidateMetric(eval.getCandidateMetric())
                    .candidateCause(eval.getCandidateCause())
                    .evidenceScore(eval.getEvidenceScore())
                    .rank(currentRank)
                    .explanation(eval.getExplanation())
                    .primaryCandidate(isPrimary)
                    .createdAt(completedAt)
                    .build();
            candidateEntity = candidateRepository.save(candidateEntity);

            List<RcaEvidenceResponse> evidenceResponses = new ArrayList<>();
            for (EvidenceItem item : eval.getEvidenceItems()) {
                RcaEvidenceEntity evidenceEntity = RcaEvidenceEntity.builder()
                        .candidateId(candidateEntity.getId())
                        .evidenceType(item.evidenceType())
                        .resourceId(item.resourceId())
                        .metricName(item.metricName())
                        .observedValue(item.observedValue())
                        .anomalyScore(item.anomalyScore())
                        .observedAt(item.observedAt())
                        .contributionScore(item.contributionScore())
                        .explanation(item.explanation())
                        .createdAt(completedAt)
                        .build();
                evidenceEntity = evidenceRepository.save(evidenceEntity);

                evidenceResponses.add(new RcaEvidenceResponse(
                        evidenceEntity.getId(),
                        evidenceEntity.getCandidateId(),
                        evidenceEntity.getEvidenceType(),
                        evidenceEntity.getResourceId(),
                        evidenceEntity.getMetricName(),
                        evidenceEntity.getObservedValue(),
                        evidenceEntity.getAnomalyScore(),
                        evidenceEntity.getObservedAt(),
                        evidenceEntity.getContributionScore(),
                        evidenceEntity.getExplanation(),
                        evidenceEntity.getCreatedAt()
                ));
            }

            candidateResponses.add(new RcaCandidateResponse(
                    candidateEntity.getId(),
                    candidateEntity.getAnalysisId(),
                    candidateEntity.getCandidateResourceId(),
                    candidateEntity.getCandidateMetric(),
                    candidateEntity.getCandidateCause(),
                    candidateEntity.getEvidenceScore(),
                    candidateEntity.getRank(),
                    candidateEntity.getExplanation(),
                    candidateEntity.getPrimaryCandidate(),
                    candidateEntity.getCreatedAt(),
                    evidenceResponses
            ));

            currentRank++;
        }

        log.info("Finished deterministic RCA analysis {} for incident {} (status: {}, confidence: {})",
                analysisEntity.getId(), incidentId, status, confidence);

        return new RcaAnalysisResponse(
                analysisEntity.getId(),
                analysisEntity.getIncidentId(),
                analysisEntity.getStatus(),
                analysisEntity.getInvestigatedResourceId(),
                analysisEntity.getSummary(),
                analysisEntity.getConfidence(),
                confidenceLvl,
                analysisEntity.getStartedAt(),
                analysisEntity.getCompletedAt(),
                analysisEntity.getCreatedAt(),
                candidateResponses
        );
    }

    @Override
    @Transactional(readOnly = true)
    public RcaAnalysisResponse getLatestAnalysisByIncidentId(UUID incidentId) {
        if (!incidentEvidenceQuery.existsIncident(incidentId)) {
            throw new ResourceNotFoundException("Incident not found with ID: " + incidentId);
        }

        RcaAnalysisEntity analysis = analysisRepository.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("No RCA analysis found for incident ID: " + incidentId));

        return buildAnalysisResponse(analysis);
    }

    @Override
    @Transactional(readOnly = true)
    public RcaAnalysisResponse getAnalysisById(UUID incidentId, UUID analysisId) {
        if (!incidentEvidenceQuery.existsIncident(incidentId)) {
            throw new ResourceNotFoundException("Incident not found with ID: " + incidentId);
        }

        RcaAnalysisEntity analysis = analysisRepository.findByIdAndIncidentId(analysisId, incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("RCA analysis not found with ID: " + analysisId + " for incident: " + incidentId));

        return buildAnalysisResponse(analysis);
    }

    private RcaAnalysisResponse buildAnalysisResponse(RcaAnalysisEntity analysis) {
        List<RcaCandidateEntity> candidates = candidateRepository.findByAnalysisIdOrderByRankAsc(analysis.getId());
        List<RcaCandidateResponse> candidateResponses = new ArrayList<>();

        for (RcaCandidateEntity candidate : candidates) {
            List<RcaEvidenceEntity> evidenceList = evidenceRepository
                    .findByCandidateIdOrderByContributionScoreDesc(candidate.getId());

            List<RcaEvidenceResponse> evidenceResponses = evidenceList.stream()
                    .map(e -> new RcaEvidenceResponse(
                            e.getId(),
                            e.getCandidateId(),
                            e.getEvidenceType(),
                            e.getResourceId(),
                            e.getMetricName(),
                            e.getObservedValue(),
                            e.getAnomalyScore(),
                            e.getObservedAt(),
                            e.getContributionScore(),
                            e.getExplanation(),
                            e.getCreatedAt()
                    ))
                    .toList();

            candidateResponses.add(new RcaCandidateResponse(
                    candidate.getId(),
                    candidate.getAnalysisId(),
                    candidate.getCandidateResourceId(),
                    candidate.getCandidateMetric(),
                    candidate.getCandidateCause(),
                    candidate.getEvidenceScore(),
                    candidate.getRank(),
                    candidate.getExplanation(),
                    candidate.getPrimaryCandidate(),
                    candidate.getCreatedAt(),
                    evidenceResponses
            ));
        }

        return new RcaAnalysisResponse(
                analysis.getId(),
                analysis.getIncidentId(),
                analysis.getStatus(),
                analysis.getInvestigatedResourceId(),
                analysis.getSummary(),
                analysis.getConfidence(),
                mapConfidenceLevel(analysis.getConfidence()),
                analysis.getStartedAt(),
                analysis.getCompletedAt(),
                analysis.getCreatedAt(),
                candidateResponses
        );
    }

    private CandidateEvaluation evaluateCandidate(
            UUID candidateId,
            String candidateName,
            UUID investigatedResourceId,
            String investigatedResourceName,
            boolean isDirectDependency,
            boolean isDirectDependent,
            Instant incidentRefTime,
            Instant windowStart,
            Instant windowEnd,
            List<IncidentAnomalyEvidenceEntity> incidentEvidenceList,
            Instant incidentDetectionTime,
            UUID targetIncidentId) {

        List<EvidenceItem> items = new ArrayList<>();
        IncidentAnomalyEvidenceEntity candidateAnomaly = null;

        // 1. ANOMALY EVIDENCE (+0.40)
        if (candidateId.equals(investigatedResourceId)) {
            if (!incidentEvidenceList.isEmpty()) {
                candidateAnomaly = incidentEvidenceList.get(0);
            }
        } else {
            List<IncidentAnomalyEvidenceEntity> anomalies = incidentEvidenceQuery
                    .findEvidenceForResourceInWindow(candidateId, windowStart, windowEnd);
            if (!anomalies.isEmpty()) {
                candidateAnomaly = anomalies.get(0);
            }
        }

        if (candidateAnomaly != null) {
            items.add(new EvidenceItem(
                    RcaEvidenceType.ANOMALY,
                    candidateId,
                    candidateAnomaly.getMetricName(),
                    candidateAnomaly.getObservedValue(),
                    candidateAnomaly.getAnomalyScore(),
                    candidateAnomaly.getObservedAt(),
                    WEIGHT_ANOMALY,
                    String.format("Candidate resource %s exhibited anomalous %s of %.2f (anomaly score: %.2f) at %s.",
                            candidateName,
                            candidateAnomaly.getMetricName(),
                            candidateAnomaly.getObservedValue(),
                            candidateAnomaly.getAnomalyScore(),
                            candidateAnomaly.getObservedAt())
            ));
        }

        // 2. TEMPORAL PRECEDENCE EVIDENCE (+0.25)
        Long precedenceSeconds = null;
        if (candidateAnomaly != null && !candidateId.equals(investigatedResourceId)) {
            if (candidateAnomaly.getObservedAt().isBefore(incidentRefTime)) {
                long diff = Duration.between(candidateAnomaly.getObservedAt(), incidentRefTime).getSeconds();
                precedenceSeconds = diff;
                items.add(new EvidenceItem(
                        RcaEvidenceType.TEMPORAL_PRECEDENCE,
                        candidateId,
                        candidateAnomaly.getMetricName(),
                        (double) diff,
                        null,
                        candidateAnomaly.getObservedAt(),
                        WEIGHT_TEMPORAL_PRECEDENCE,
                        String.format("%s anomaly preceded the incident anomaly by %d seconds.",
                                candidateName, diff)
                ));
            }
        }

        // 3. DEPENDENCY RELATIONSHIP EVIDENCE (Learned edge weight w_final in [0.05, 0.40] or static fallback 0.20)
        if (isDirectDependency) {
            EdgeWeightResult edgeResult = edgeWeightProvider != null
                    ? edgeWeightProvider.getEdgeWeight(investigatedResourceId, candidateId, incidentDetectionTime, targetIncidentId)
                    : EdgeWeightResult.fallback(WEIGHT_DEPENDENCY, "Static baseline dependency weight (prior: 0.20).");

            items.add(new EvidenceItem(
                    RcaEvidenceType.DEPENDENCY,
                    candidateId,
                    null,
                    null,
                    null,
                    null,
                    edgeResult.finalWeight(),
                    String.format("%s directly depends on %s (DEPENDS_ON). %s",
                            investigatedResourceName, candidateName, edgeResult.explanation())
            ));
        }

        // 4. TELEMETRY CORRELATION EVIDENCE (+0.15)
        List<TelemetryEventEntity> candidateTelemetry = telemetryHistoryQuery
                .findTelemetryInWindow(candidateId, windowStart, windowEnd);

        if (!candidateTelemetry.isEmpty()) {
            TelemetryEventEntity correlatedTelemetry = findCorrelatedTelemetry(candidateId, candidateTelemetry, candidateAnomaly);
            if (correlatedTelemetry != null) {
                items.add(new EvidenceItem(
                        RcaEvidenceType.TELEMETRY_CORRELATION,
                        candidateId,
                        correlatedTelemetry.getMetricName(),
                        correlatedTelemetry.getValue(),
                        null,
                        correlatedTelemetry.getTimestamp(),
                        WEIGHT_TELEMETRY_CORRELATION,
                        String.format("Telemetry for metric '%s' exhibited abnormal correlation during the incident window (observed value: %.2f).",
                                correlatedTelemetry.getMetricName(), correlatedTelemetry.getValue())
                ));
            }
        }

        // If no evidence of any kind, do not generate candidate
        if (items.isEmpty()) {
            return null;
        }

        double totalScore = items.stream().mapToDouble(EvidenceItem::contributionScore).sum();
        totalScore = roundScore(Math.min(1.0, totalScore));

        String candidateMetric = candidateAnomaly != null ? candidateAnomaly.getMetricName() : null;
        String candidateCause = formatCandidateCause(candidateName, candidateMetric, candidateAnomaly, isDirectDependency, isDirectDependent);
        String explanation = formatExplanation(candidateName, investigatedResourceName, isDirectDependency, isDirectDependent, candidateAnomaly, precedenceSeconds, items);

        return new CandidateEvaluation(
                candidateId,
                candidateName,
                candidateMetric,
                candidateCause,
                totalScore,
                precedenceSeconds,
                explanation,
                items
        );
    }

    private TelemetryEventEntity findCorrelatedTelemetry(
            UUID candidateId,
            List<TelemetryEventEntity> telemetryEvents,
            IncidentAnomalyEvidenceEntity candidateAnomaly) {

        for (TelemetryEventEntity event : telemetryEvents) {
            // Check if telemetry event was evaluated as anomalous
            try {
                AnomalyDetectionResponse response = anomalyDetectionService.evaluateAnomaly(
                        candidateId, event.getMetricName(), event.getValue(), null);
                if (response != null && response.status() == AnomalyStatus.ANOMALOUS) {
                    // Only award if it represents a distinct correlated metric or additional correlated observation
                    if (candidateAnomaly == null || !event.getMetricName().equalsIgnoreCase(candidateAnomaly.getMetricName())
                            || !event.getTimestamp().equals(candidateAnomaly.getObservedAt())) {
                        return event;
                    }
                }
            } catch (Exception ex) {
                log.debug("Telemetry correlation evaluation skipped for metric {}: {}", event.getMetricName(), ex.getMessage());
            }
        }
        return null;
    }

    private boolean hasAnomalousEvidence(CandidateEvaluation eval) {
        return eval.getEvidenceItems().stream()
                .anyMatch(i -> i.evidenceType() == RcaEvidenceType.ANOMALY || i.evidenceType() == RcaEvidenceType.TELEMETRY_CORRELATION);
    }

    private String formatCandidateCause(
            String candidateName,
            String metricName,
            IncidentAnomalyEvidenceEntity anomaly,
            boolean isDependency,
            boolean isDependent) {

        if (anomaly != null && metricName != null) {
            String lower = metricName.toLowerCase();
            if (lower.contains("connection")) {
                return "Database connection pool exhaustion on " + candidateName;
            } else if (lower.contains("cpu")) {
                return "CPU saturation on " + candidateName;
            } else if (lower.contains("latency")) {
                return "Latency degradation on " + candidateName;
            } else if (lower.contains("memory")) {
                return "Memory pressure on " + candidateName;
            } else {
                return "Anomalous " + metricName.replace('_', ' ') + " on " + candidateName;
            }
        }

        if (isDependency) {
            return "Direct dependency with normal telemetry";
        }
        if (isDependent) {
            return "Direct dependent with normal telemetry";
        }
        return "Investigated resource internal baseline";
    }

    private String formatExplanation(
            String candidateName,
            String investigatedResourceName,
            boolean isDependency,
            boolean isDependent,
            IncidentAnomalyEvidenceEntity anomaly,
            Long precedenceSeconds,
            List<EvidenceItem> items) {

        StringBuilder sb = new StringBuilder();

        if (isDependency && anomaly != null && precedenceSeconds != null) {
            sb.append(String.format("%s is a candidate root cause because it is a direct dependency of %s, exhibited anomalous %s during the investigation window, and its anomaly preceded the incident anomaly by %d seconds.",
                    candidateName, investigatedResourceName, anomaly.getMetricName(), precedenceSeconds));
        } else if (isDependency && anomaly != null) {
            sb.append(String.format("%s is a candidate root cause because it is a direct dependency of %s and exhibited anomalous %s during the investigation window, though its anomaly did not precede the incident anomaly.",
                    candidateName, investigatedResourceName, anomaly.getMetricName()));
        } else if (isDependency) {
            sb.append(String.format("%s is a direct dependency of %s, but exhibited no anomalous telemetry or evidence during the investigation window.",
                    candidateName, investigatedResourceName));
        } else if (isDependent && anomaly != null) {
            sb.append(String.format("%s is a downstream dependent of %s that exhibited anomalous %s during the investigation window.",
                    candidateName, investigatedResourceName, anomaly.getMetricName()));
        } else if (anomaly != null) {
            sb.append(String.format("%s is an internal candidate cause because it exhibited anomalous %s during the incident window.",
                    candidateName, anomaly.getMetricName()));
        } else {
            sb.append(String.format("%s was inspected as part of the dependency graph during the investigation window.",
                    candidateName));
        }

        boolean hasTelemetryCorrelation = items.stream().anyMatch(i -> i.evidenceType() == RcaEvidenceType.TELEMETRY_CORRELATION);
        if (hasTelemetryCorrelation) {
            sb.append(" Correlated telemetry movement was also observed during the incident timeframe.");
        }

        return sb.toString();
    }

    private double roundScore(double score) {
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private String mapConfidenceLevel(double score) {
        if (score < 0.30) {
            return "LOW";
        } else if (score < 0.60) {
            return "MODERATE";
        } else if (score < 0.80) {
            return "HIGH";
        } else {
            return "VERY_HIGH";
        }
    }

    private record EvidenceItem(
            RcaEvidenceType evidenceType,
            UUID resourceId,
            String metricName,
            Double observedValue,
            Double anomalyScore,
            Instant observedAt,
            double contributionScore,
            String explanation
    ) {
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    private static class CandidateEvaluation {
        private final UUID candidateResourceId;
        private final String candidateName;
        private final String candidateMetric;
        private final String candidateCause;
        private final double evidenceScore;
        private final Long precedenceSeconds;
        private final String explanation;
        private final List<EvidenceItem> evidenceItems;

        public boolean hasEvidence() {
            return !evidenceItems.isEmpty();
        }
    }
}
