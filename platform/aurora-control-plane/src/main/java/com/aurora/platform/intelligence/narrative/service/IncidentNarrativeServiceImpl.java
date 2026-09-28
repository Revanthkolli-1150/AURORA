package com.aurora.platform.intelligence.narrative.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.service.ResourceDependencyService;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.intelligence.historical.service.HistoricalIncidentService;
import com.aurora.platform.intelligence.narrative.application.dto.HistoricalIncidentSummary;
import com.aurora.platform.intelligence.narrative.application.dto.IncidentNarrativeContext;
import com.aurora.platform.intelligence.narrative.application.dto.RcaCandidateSummary;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmClientPort;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmGenerationResult;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmPromptRequest;
import com.aurora.platform.intelligence.narrative.domain.DataSanitizer;
import com.aurora.platform.intelligence.narrative.domain.IncidentNarrativeEntity;
import com.aurora.platform.intelligence.narrative.domain.RunbookSafetyValidator;
import com.aurora.platform.intelligence.narrative.dto.IncidentNarrativeResponse;
import com.aurora.platform.intelligence.narrative.dto.NarrativeMetadataResponse;
import com.aurora.platform.intelligence.narrative.infrastructure.adapter.NarrativeProperties;
import com.aurora.platform.intelligence.narrative.repository.IncidentNarrativeRepository;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.dto.RcaEvidenceResponse;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.service.ResourceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production implementation of {@link IncidentNarrativeService} orchestrating factual context assembly,
 * data sanitization, LLM prompt generation, single-flight concurrent idempotency, and deterministic fallback synthesis.
 */
@Service
public class IncidentNarrativeServiceImpl implements IncidentNarrativeService {

    private static final Logger log = LoggerFactory.getLogger(IncidentNarrativeServiceImpl.class);
    private static final String PROMPT_VERSION = "operator-narrative-v1.0.0";
    private static final String PROMPT_PATH = "prompts/operator-narrative-v1.st";

    private final IncidentService incidentService;
    private final ResourceService resourceService;
    private final RcaAnalysisService rcaAnalysisService;
    private final HistoricalIncidentService historicalIncidentService;
    private final ResourceDependencyService dependencyService;
    private final LlmClientPort llmClientPort;
    private final IncidentNarrativeRepository narrativeRepository;
    private final NarrativeProperties properties;
    private final ObjectMapper objectMapper;

    // Single-flight in-flight generation map guaranteeing at most one provider call per RCA analysis ID
    private final ConcurrentHashMap<UUID, CompletableFuture<IncidentNarrativeResponse>> inFlightGenerations = new ConcurrentHashMap<>();

    private String cachedPromptTemplate;

    public IncidentNarrativeServiceImpl(
            IncidentService incidentService,
            ResourceService resourceService,
            RcaAnalysisService rcaAnalysisService,
            HistoricalIncidentService historicalIncidentService,
            ResourceDependencyService dependencyService,
            LlmClientPort llmClientPort,
            IncidentNarrativeRepository narrativeRepository,
            NarrativeProperties properties,
            ObjectMapper objectMapper
    ) {
        this.incidentService = incidentService;
        this.resourceService = resourceService;
        this.rcaAnalysisService = rcaAnalysisService;
        this.historicalIncidentService = historicalIncidentService;
        this.dependencyService = dependencyService;
        this.llmClientPort = llmClientPort;
        this.narrativeRepository = narrativeRepository;
        this.properties = properties;
        this.objectMapper = objectMapper != null
                ? objectMapper.copy().findAndRegisterModules()
                : new ObjectMapper().findAndRegisterModules();
    }

    @Override
    public IncidentNarrativeResponse generateOrGetNarrative(UUID incidentId) {
        log.info("Processing narrative request for incident ID: {}", incidentId);

        // 1. Verify incident exists
        IncidentResponse incident = incidentService.getIncidentById(incidentId);

        // 2. Obtain latest deterministic RCA analysis
        RcaAnalysisResponse rcaAnalysis = rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId);
        if (rcaAnalysis == null) {
            throw new ResourceNotFoundException("No completed RCA analysis found for incident ID: " + incidentId);
        }

        UUID rcaAnalysisId = rcaAnalysis.id();

        // 3. Fast-path check: is narrative already persisted?
        var existing = narrativeRepository.findByRcaAnalysisId(rcaAnalysisId);
        if (existing.isPresent()) {
            log.info("Returning cached narrative for RCA analysis ID: {}", rcaAnalysisId);
            return mapEntityToResponse(existing.get());
        }

        // 4. Single-flight coordination: ensure at most ONE in-flight generation per rcaAnalysisId
        CompletableFuture<IncidentNarrativeResponse> myFuture = new CompletableFuture<>();
        CompletableFuture<IncidentNarrativeResponse> existingFuture = inFlightGenerations.putIfAbsent(rcaAnalysisId, myFuture);

        if (existingFuture != null) {
            // Another thread is already generating the narrative for this RCA analysis; wait for its result
            log.debug("Awaiting concurrent in-flight generation for RCA analysis ID: {}", rcaAnalysisId);
            return existingFuture.join();
        }

        // Current thread is the single-flight leader
        try {
            // Double-check DB in case another node/thread just completed persistence
            var doubleCheck = narrativeRepository.findByRcaAnalysisId(rcaAnalysisId);
            if (doubleCheck.isPresent()) {
                IncidentNarrativeResponse resp = mapEntityToResponse(doubleCheck.get());
                myFuture.complete(resp);
                return resp;
            }

            IncidentNarrativeResponse response = executeGenerationAndPersist(incident, rcaAnalysis);
            myFuture.complete(response);
            return response;
        } catch (Throwable t) {
            myFuture.completeExceptionally(t);
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Failed to generate incident narrative", t);
        } finally {
            inFlightGenerations.remove(rcaAnalysisId, myFuture);
        }
    }

    @Transactional
    protected IncidentNarrativeResponse executeGenerationAndPersist(IncidentResponse incident, RcaAnalysisResponse rcaAnalysis) {
        long startTime = System.currentTimeMillis();

        // 1. Assemble factual deterministic context
        IncidentNarrativeContext rawContext = assembleContext(incident, rcaAnalysis);

        // 2. Sanitize external-boundary context
        IncidentNarrativeContext sanitizedContext = DataSanitizer.sanitize(rawContext);

        // 3. Invoke LLM or Fallback with structural safety checks
        IncidentNarrativeEntity entity = generateNarrativeEntity(incident, rcaAnalysis, sanitizedContext, startTime);

        // 4. Persist narrative with race condition recovery
        try {
            IncidentNarrativeEntity saved = narrativeRepository.save(entity);
            log.info("Successfully persisted narrative ID: {} for incident ID: {} (fallbackUsed: {})",
                    saved.getId(), incident.id(), saved.getFallbackUsed());
            return mapEntityToResponse(saved);
        } catch (DataIntegrityViolationException dive) {
            // In case of multi-node race where unique constraint uq_incident_narratives_analysis was triggered
            log.info("Concurrent insert caught by unique constraint; retrieving persisted record for RCA analysis ID: {}",
                    rcaAnalysis.id());
            return narrativeRepository.findByRcaAnalysisId(rcaAnalysis.id())
                    .map(this::mapEntityToResponse)
                    .orElseThrow(() -> dive);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public IncidentNarrativeResponse getNarrative(UUID incidentId) {
        log.debug("Retrieving latest narrative for incident ID: {}", incidentId);
        // Verify incident exists
        incidentService.getIncidentById(incidentId);

        IncidentNarrativeEntity entity = narrativeRepository.findTopByIncidentIdOrderByCreatedAtDesc(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("No narrative found for incident ID: " + incidentId));

        return mapEntityToResponse(entity);
    }

    private IncidentNarrativeEntity generateNarrativeEntity(
            IncidentResponse incident,
            RcaAnalysisResponse rcaAnalysis,
            IncidentNarrativeContext sanitizedContext,
            long startTime
    ) {
        if (!properties.isEnabled()) {
            log.info("LLM generation is disabled; synthesizing deterministic fallback narrative");
            return createDeterministicFallback(incident, rcaAnalysis, sanitizedContext, startTime);
        }

        try {
            String template = getPromptTemplate();
            String contextJson = objectMapper.writeValueAsString(sanitizedContext);
            String userPrompt = template.replace("$incidentContextJson$", contextJson);

            LlmPromptRequest promptRequest = new LlmPromptRequest(
                    "You are AURORA AI Incident Copilot. Follow all invariants and output strictly in JSON format.",
                    userPrompt,
                    "JSON",
                    properties.getTemperature(),
                    properties.getTimeoutMs(),
                    Map.of("incidentId", incident.id().toString(), "rcaAnalysisId", rcaAnalysis.id().toString())
            );

            LlmGenerationResult result = llmClientPort.generate(promptRequest);

            if (!result.successful() || result.structuredJson() == null || result.structuredJson().isBlank()) {
                log.warn("LLM generation unsuccessful: {}. Using deterministic fallback", result.errorMessage());
                return createDeterministicFallback(incident, rcaAnalysis, sanitizedContext, startTime);
            }

            JsonNode root = objectMapper.readTree(result.structuredJson());

            String headline = root.path("headline").asText("");
            String executiveSummary = root.path("executiveSummary").asText("");
            String rootCauseExplanation = root.path("rootCauseExplanation").asText("");

            if (headline.isBlank() || executiveSummary.isBlank() || rootCauseExplanation.isBlank()) {
                log.warn("LLM returned incomplete required fields. Using deterministic fallback");
                return createDeterministicFallback(incident, rcaAnalysis, sanitizedContext, startTime);
            }

            List<String> observedSymptoms = readStringList(root.path("observedSymptoms"));
            List<String> secondaryHypotheses = readStringList(root.path("secondaryHypothesesEvaluated"));
            String historicalContext = root.path("historicalContextNarrative").asText(null);
            List<String> investigationSteps = readStringList(root.path("suggestedInvestigationSteps"));
            List<String> caveats = readStringList(root.path("caveatsAndUncertainties"));

            // Structural Runbook Safety Check
            if (!RunbookSafetyValidator.isSafe(investigationSteps)) {
                log.warn("LLM returned executable commands in suggested investigation steps. Tripping to deterministic fallback.");
                return createDeterministicFallback(incident, rcaAnalysis, sanitizedContext, startTime);
            }

            // General text safety check on headline, summary, and root cause
            if (!RunbookSafetyValidator.isSafeStep(headline)
                    || !RunbookSafetyValidator.isSafeStep(executiveSummary)
                    || !RunbookSafetyValidator.isSafeStep(rootCauseExplanation)) {
                log.warn("LLM returned executable commands in narrative text fields. Tripping to deterministic fallback.");
                return createDeterministicFallback(incident, rcaAnalysis, sanitizedContext, startTime);
            }

            int durationMs = (int) (System.currentTimeMillis() - startTime);

            return IncidentNarrativeEntity.builder()
                    .id(UUID.randomUUID())
                    .incidentId(incident.id())
                    .rcaAnalysisId(rcaAnalysis.id())
                    .headline(truncate(headline, 255))
                    .executiveSummary(executiveSummary)
                    .observedSymptoms(objectMapper.writeValueAsString(observedSymptoms))
                    .rootCauseExplanation(rootCauseExplanation)
                    .secondaryHypotheses(objectMapper.writeValueAsString(secondaryHypotheses))
                    .historicalContext(historicalContext)
                    .investigationSteps(objectMapper.writeValueAsString(investigationSteps))
                    .caveats(objectMapper.writeValueAsString(caveats))
                    .provider(result.provider())
                    .modelName(result.modelName())
                    .promptVersion(PROMPT_VERSION)
                    .fallbackUsed(false)
                    .generationDurationMs(durationMs)
                    .createdAt(Instant.now())
                    .build();

        } catch (Exception e) {
            log.warn("Exception during LLM narrative generation: {}. Using deterministic fallback", e.getMessage(), e);
            return createDeterministicFallback(incident, rcaAnalysis, sanitizedContext, startTime);
        }
    }

    private IncidentNarrativeEntity createDeterministicFallback(
            IncidentResponse incident,
            RcaAnalysisResponse rcaAnalysis,
            IncidentNarrativeContext context,
            long startTime
    ) {
        int durationMs = (int) (System.currentTimeMillis() - startTime);

        String headline = String.format("%s incident on %s: %s",
                incident.severity(), context.investigatedResourceName(), incident.title());

        String executiveSummary = rcaAnalysis.summary();

        List<String> symptoms = new ArrayList<>();
        if (context.primaryCandidate() != null && context.primaryCandidate().evidenceItemSummaries() != null) {
            symptoms.addAll(context.primaryCandidate().evidenceItemSummaries());
        }
        if (symptoms.isEmpty()) {
            symptoms.add("Telemetry anomaly detected on " + context.investigatedResourceName());
        }

        String rootCauseExplanation;
        if (context.primaryCandidate() != null) {
            rootCauseExplanation = String.format(
                    "Deterministic root cause isolated to %s (%s) with evidence score %.2f. Primary cause: %s. Explanation: %s",
                    context.primaryCandidate().candidateResourceName(),
                    context.primaryCandidate().candidateResourceId(),
                    context.primaryCandidate().evidenceScore(),
                    context.primaryCandidate().candidateCause(),
                    context.primaryCandidate().explanation()
            );
        } else {
            rootCauseExplanation = "Deterministic RCA did not isolate a single primary root cause with sufficient confidence. "
                    + rcaAnalysis.summary();
        }

        List<String> secondaryHypotheses = new ArrayList<>();
        if (context.topSecondaryCandidates() != null) {
            for (RcaCandidateSummary secondary : context.topSecondaryCandidates()) {
                secondaryHypotheses.add(String.format("Candidate %s (cause: %s, score: %.2f)",
                    secondary.candidateResourceName(), secondary.candidateCause(), secondary.evidenceScore()));
            }
        }

        String historicalContext;
        if (context.similarHistoricalIncidents() != null && !context.similarHistoricalIncidents().isEmpty()) {
            historicalContext = String.format("Identified %d historically similar resolved incident(s) across the fleet.",
                    context.similarHistoricalIncidents().size());
        } else {
            historicalContext = "No structurally similar resolved incidents found in the historical fleet corpus.";
        }

        List<String> investigationSteps = new ArrayList<>();
        investigationSteps.add("Inspect telemetry metrics and resource health on " + context.investigatedResourceName());
        if (context.upstreamDependencies() != null && !context.upstreamDependencies().isEmpty()) {
            investigationSteps.add("Verify connectivity and error rates with upstream dependencies: "
                    + String.join(", ", context.upstreamDependencies()));
        }
        investigationSteps.add("Review recent configuration, deployment, or traffic changes in the 10-minute lookback window");

        List<String> caveats = List.of("Synthesized via deterministic fallback generator due to LLM provider unavailability, timeout, or configuration.");

        try {
            return IncidentNarrativeEntity.builder()
                    .id(UUID.randomUUID())
                    .incidentId(incident.id())
                    .rcaAnalysisId(rcaAnalysis.id())
                    .headline(truncate(headline, 255))
                    .executiveSummary(executiveSummary)
                    .observedSymptoms(objectMapper.writeValueAsString(symptoms))
                    .rootCauseExplanation(rootCauseExplanation)
                    .secondaryHypotheses(objectMapper.writeValueAsString(secondaryHypotheses))
                    .historicalContext(historicalContext)
                    .investigationSteps(objectMapper.writeValueAsString(investigationSteps))
                    .caveats(objectMapper.writeValueAsString(caveats))
                    .provider(properties.getProvider() != null ? properties.getProvider() : "deterministic-fallback")
                    .modelName(properties.getModel() != null ? properties.getModel() : "deterministic-rules")
                    .promptVersion(PROMPT_VERSION)
                    .fallbackUsed(true)
                    .generationDurationMs(durationMs)
                    .createdAt(Instant.now())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize fallback narrative payload", e);
        }
    }

    private IncidentNarrativeContext assembleContext(IncidentResponse incident, RcaAnalysisResponse rcaAnalysis) {
        ResourceResponse resource = resourceService.getResourceById(incident.resourceId());

        // Extract dependencies
        List<String> upstream = new ArrayList<>();
        try {
            List<ResourceDependencyResponse> deps = dependencyService.getDependencies(incident.resourceId());
            if (deps != null) {
                for (ResourceDependencyResponse dep : deps) {
                    try {
                        ResourceResponse target = resourceService.getResourceById(dep.targetResourceId());
                        upstream.add(target.name());
                    } catch (Exception ignored) {
                        upstream.add(dep.targetResourceId().toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve upstream dependencies for resource {}: {}", incident.resourceId(), e.getMessage());
        }

        List<String> downstream = new ArrayList<>();
        try {
            List<ResourceDependencyResponse> dependents = dependencyService.getDependents(incident.resourceId());
            if (dependents != null) {
                for (ResourceDependencyResponse dep : dependents) {
                    try {
                        ResourceResponse source = resourceService.getResourceById(dep.sourceResourceId());
                        downstream.add(source.name());
                    } catch (Exception ignored) {
                        downstream.add(dep.sourceResourceId().toString());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve downstream dependents for resource {}: {}", incident.resourceId(), e.getMessage());
        }

        // Extract RCA candidates
        RcaCandidateSummary primaryCandidate = null;
        List<RcaCandidateSummary> topSecondary = new ArrayList<>();

        if (rcaAnalysis.candidates() != null) {
            for (RcaCandidateResponse candidate : rcaAnalysis.candidates()) {
                String candidateName = candidate.candidateResourceId().toString();
                try {
                    ResourceResponse candidateResource = resourceService.getResourceById(candidate.candidateResourceId());
                    candidateName = candidateResource.name();
                } catch (Exception ignored) {
                }

                List<String> evidenceSummaries = new ArrayList<>();
                if (candidate.evidence() != null) {
                    for (RcaEvidenceResponse ev : candidate.evidence()) {
                        evidenceSummaries.add(ev.explanation());
                    }
                }

                RcaCandidateSummary summary = new RcaCandidateSummary(
                        candidate.candidateResourceId(),
                        candidateName,
                        candidate.candidateMetric(),
                        candidate.candidateCause(),
                        candidate.evidenceScore(),
                        candidate.rank(),
                        candidate.explanation(),
                        candidate.primaryCandidate(),
                        evidenceSummaries
                );

                if (Boolean.TRUE.equals(candidate.primaryCandidate())) {
                    primaryCandidate = summary;
                } else if (topSecondary.size() < 3) {
                    topSecondary.add(summary);
                }
            }
        }

        // Extract historical similarities
        List<HistoricalIncidentSummary> historicalSummaries = new ArrayList<>();
        try {
            List<SimilarIncidentResponse> similar = historicalIncidentService.findSimilarIncidents(incident.id(), 3, 0.30);
            if (similar != null) {
                for (SimilarIncidentResponse s : similar) {
                    historicalSummaries.add(new HistoricalIncidentSummary(
                            s.historicalIncidentId(),
                            s.resourceName(),
                            s.similarityScore(),
                            s.primaryRcaCause(),
                            s.resolutionDurationSeconds()
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query historical incident similarities: {}", e.getMessage());
        }

        return new IncidentNarrativeContext(
                incident.id(),
                incident.title(),
                incident.description(),
                incident.severity(),
                incident.status(),
                incident.detectedAt(),
                resource.id(),
                resource.name(),
                resource.type(),
                resource.environment(),
                rcaAnalysis.id(),
                rcaAnalysis.confidence(),
                rcaAnalysis.confidenceLevel(),
                rcaAnalysis.summary(),
                primaryCandidate,
                topSecondary,
                historicalSummaries,
                upstream,
                downstream,
                Instant.now()
        );
    }

    private synchronized String getPromptTemplate() {
        if (cachedPromptTemplate != null) {
            return cachedPromptTemplate;
        }

        try {
            ClassPathResource resource = new ClassPathResource(PROMPT_PATH);
            try (InputStream is = resource.getInputStream()) {
                cachedPromptTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return cachedPromptTemplate;
            }
        } catch (Exception e) {
            log.warn("Failed to load prompt template from {}: {}. Using built-in fallback template", PROMPT_PATH, e.getMessage());
            cachedPromptTemplate = "Synthesize an SRE narrative for this incident context in JSON: $incidentContextJson$";
            return cachedPromptTemplate;
        }
    }

    private IncidentNarrativeResponse mapEntityToResponse(IncidentNarrativeEntity entity) {
        List<String> symptoms = deserializeStringList(entity.getObservedSymptoms());
        List<String> hypotheses = deserializeStringList(entity.getSecondaryHypotheses());
        List<String> steps = deserializeStringList(entity.getInvestigationSteps());
        List<String> caveats = deserializeStringList(entity.getCaveats());

        NarrativeMetadataResponse metadata = new NarrativeMetadataResponse(
                entity.getProvider(),
                entity.getModelName(),
                entity.getPromptVersion(),
                entity.getFallbackUsed(),
                entity.getGenerationDurationMs(),
                entity.getCreatedAt()
        );

        return new IncidentNarrativeResponse(
                entity.getId(),
                entity.getIncidentId(),
                entity.getRcaAnalysisId(),
                entity.getHeadline(),
                entity.getExecutiveSummary(),
                symptoms,
                entity.getRootCauseExplanation(),
                hypotheses,
                entity.getHistoricalContext(),
                steps,
                caveats,
                metadata
        );
    }

    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON string list: {}", json);
            return Collections.singletonList(json);
        }
    }

    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                list.add(item.asText());
            }
        }
        return list;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
