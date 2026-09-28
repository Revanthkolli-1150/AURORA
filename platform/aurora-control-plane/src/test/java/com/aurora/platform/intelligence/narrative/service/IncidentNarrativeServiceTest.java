package com.aurora.platform.intelligence.narrative.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.service.ResourceDependencyService;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.service.IncidentService;
import com.aurora.platform.intelligence.historical.service.HistoricalIncidentService;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmClientPort;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmGenerationResult;
import com.aurora.platform.intelligence.narrative.application.port.out.LlmPromptRequest;
import com.aurora.platform.intelligence.narrative.domain.IncidentNarrativeEntity;
import com.aurora.platform.intelligence.narrative.dto.IncidentNarrativeResponse;
import com.aurora.platform.intelligence.narrative.infrastructure.adapter.NarrativeProperties;
import com.aurora.platform.intelligence.narrative.repository.IncidentNarrativeRepository;
import com.aurora.platform.intelligence.rca.dto.RcaAnalysisResponse;
import com.aurora.platform.intelligence.rca.dto.RcaCandidateResponse;
import com.aurora.platform.intelligence.rca.dto.RcaEvidenceResponse;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisStatus;
import com.aurora.platform.intelligence.rca.entity.RcaEvidenceType;
import com.aurora.platform.intelligence.rca.service.RcaAnalysisService;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentNarrativeServiceTest {

    private IncidentService incidentService;
    private ResourceService resourceService;
    private RcaAnalysisService rcaAnalysisService;
    private HistoricalIncidentService historicalIncidentService;
    private ResourceDependencyService dependencyService;
    private LlmClientPort llmClientPort;
    private IncidentNarrativeRepository narrativeRepository;
    private NarrativeProperties properties;
    private ObjectMapper objectMapper;

    private IncidentNarrativeServiceImpl service;

    private UUID incidentId;
    private UUID resourceId;
    private UUID rcaAnalysisId;
    private UUID upstreamResourceId;

    @BeforeEach
    void setUp() {
        incidentService = mock(IncidentService.class);
        resourceService = mock(ResourceService.class);
        rcaAnalysisService = mock(RcaAnalysisService.class);
        historicalIncidentService = mock(HistoricalIncidentService.class);
        dependencyService = mock(ResourceDependencyService.class);
        llmClientPort = mock(LlmClientPort.class);
        narrativeRepository = mock(IncidentNarrativeRepository.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        properties = new NarrativeProperties();
        properties.setEnabled(true);
        properties.setProvider("openai-compatible");
        properties.setModel("gpt-4o-mini");
        properties.setTimeoutMs(5000);
        properties.setTemperature(0.0);

        service = new IncidentNarrativeServiceImpl(
                incidentService,
                resourceService,
                rcaAnalysisService,
                historicalIncidentService,
                dependencyService,
                llmClientPort,
                narrativeRepository,
                properties,
                objectMapper
        );

        incidentId = UUID.randomUUID();
        resourceId = UUID.randomUUID();
        rcaAnalysisId = UUID.randomUUID();
        upstreamResourceId = UUID.randomUUID();
    }

    private void setupValidIncidentAndRca() {
        IncidentResponse incident = new IncidentResponse(
                incidentId,
                resourceId,
                "API Gateway High Error Rate",
                "Spike in HTTP 502 responses",
                IncidentSeverity.HIGH,
                IncidentStatus.INVESTIGATING,
                0.85,
                null,
                Instant.now(),
                null,
                Instant.now(),
                Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);

        ResourceResponse resource = new ResourceResponse(
                resourceId,
                "api-gateway",
                ResourceType.SERVICE,
                ResourceStatus.DEGRADED,
                "production",
                "srv-api-01",
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        when(resourceService.getResourceById(resourceId)).thenReturn(resource);

        ResourceResponse upstreamResource = new ResourceResponse(
                upstreamResourceId,
                "postgres-db",
                ResourceType.DATABASE,
                ResourceStatus.HEALTHY,
                "production",
                "db-01",
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        when(resourceService.getResourceById(upstreamResourceId)).thenReturn(upstreamResource);

        ResourceDependencyResponse dep = new ResourceDependencyResponse(
                UUID.randomUUID(),
                resourceId,
                upstreamResourceId,
                DependencyType.DEPENDS_ON,
                Instant.now()
        );
        when(dependencyService.getDependencies(resourceId)).thenReturn(List.of(dep));
        when(dependencyService.getDependents(resourceId)).thenReturn(List.of());

        RcaEvidenceResponse evidence = new RcaEvidenceResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                RcaEvidenceType.ANOMALY,
                upstreamResourceId,
                "db_connection_pool_active",
                95.0,
                3.8,
                Instant.now(),
                0.40,
                "Connection pool reached 95% saturation",
                Instant.now()
        );

        RcaCandidateResponse candidate = new RcaCandidateResponse(
                UUID.randomUUID(),
                rcaAnalysisId,
                upstreamResourceId,
                "db_connection_pool_active",
                "Connection pool saturation on postgres-db",
                0.85,
                1,
                "Primary candidate root cause",
                true,
                Instant.now(),
                List.of(evidence)
        );

        RcaAnalysisResponse rcaAnalysis = new RcaAnalysisResponse(
                rcaAnalysisId,
                incidentId,
                RcaAnalysisStatus.COMPLETED,
                resourceId,
                "RCA analysis completed. Primary candidate root cause: Connection pool saturation on postgres-db.",
                0.85,
                "VERY_HIGH",
                Instant.now(),
                Instant.now(),
                Instant.now(),
                List.of(candidate)
        );
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(rcaAnalysis);

        when(historicalIncidentService.findSimilarIncidents(any(), any(), any()))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("Successful LLM generation persists narrative and returns canonical response")
    void testSuccessfulGeneration() {
        setupValidIncidentAndRca();
        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.empty());

        String validLlmJson = """
                {
                  "headline": "High error rate on api-gateway due to database connection exhaustion",
                  "executiveSummary": "API Gateway experienced elevated 502 error rates caused by connection pool saturation on postgres-db.",
                  "observedSymptoms": [
                    "HTTP 502 Bad Gateway response spike on api-gateway",
                    "Database connection pool saturation at 95%"
                  ],
                  "rootCauseExplanation": "Postgres connection pool reached 95% saturation, exhausting available connections and cascading latency upstream.",
                  "secondaryHypothesesEvaluated": [
                    "Worker thread pool starvation was evaluated but showed normal utilization."
                  ],
                  "historicalContextNarrative": "No identical past incidents recorded.",
                  "suggestedInvestigationSteps": [
                    "Inspect active connection count on postgres-db",
                    "Check for long-running transactions holding table locks",
                    "Review recent database connection pool size configuration changes"
                  ],
                  "caveatsAndUncertainties": [
                    "Query telemetry granularity is 10 seconds; micro-bursts may not appear."
                  ]
                }
                """;

        when(llmClientPort.generate(any(LlmPromptRequest.class)))
                .thenReturn(LlmGenerationResult.success(
                        validLlmJson,
                        validLlmJson,
                        "openai-compatible",
                        "gpt-4o-mini",
                        250,
                        120,
                        1200L
                ));

        when(narrativeRepository.save(any(IncidentNarrativeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IncidentNarrativeResponse response = service.generateOrGetNarrative(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.headline()).contains("High error rate on api-gateway");
        assertThat(response.executiveSummary()).contains("connection pool saturation on postgres-db");
        assertThat(response.observedSymptoms()).hasSize(2);
        assertThat(response.suggestedInvestigationSteps()).hasSize(3);
        assertThat(response.metadata().fallbackUsed()).isFalse();
        assertThat(response.metadata().provider()).isEqualTo("openai-compatible");

        ArgumentCaptor<IncidentNarrativeEntity> captor = ArgumentCaptor.forClass(IncidentNarrativeEntity.class);
        verify(narrativeRepository).save(captor.capture());
        assertThat(captor.getValue().getFallbackUsed()).isFalse();
        assertThat(captor.getValue().getRcaAnalysisId()).isEqualTo(rcaAnalysisId);
    }

    @Test
    @DisplayName("Idempotency: Returns cached narrative without invoking LLM when narrative exists")
    void testIdempotentCachedRetrieval() {
        setupValidIncidentAndRca();

        IncidentNarrativeEntity existing = IncidentNarrativeEntity.builder()
                .id(UUID.randomUUID())
                .incidentId(incidentId)
                .rcaAnalysisId(rcaAnalysisId)
                .headline("Cached Headline")
                .executiveSummary("Cached Executive Summary")
                .observedSymptoms("[\"Symptom 1\"]")
                .rootCauseExplanation("Cached Explanation")
                .secondaryHypotheses("[]")
                .historicalContext("Cached Context")
                .investigationSteps("[\"Step 1\"]")
                .caveats("[]")
                .provider("openai-compatible")
                .modelName("gpt-4o-mini")
                .promptVersion("operator-narrative-v1.0.0")
                .fallbackUsed(false)
                .generationDurationMs(150)
                .createdAt(Instant.now())
                .build();

        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.of(existing));

        IncidentNarrativeResponse response = service.generateOrGetNarrative(incidentId);

        assertThat(response.headline()).isEqualTo("Cached Headline");
        assertThat(response.executiveSummary()).isEqualTo("Cached Executive Summary");
        verify(llmClientPort, never()).generate(any());
        verify(narrativeRepository, never()).save(any());
    }

    @Test
    @DisplayName("Single-Flight Concurrency: 20 concurrent requests result in exactly 1 LLM call and 1 DB save")
    void testConcurrent20WaySingleFlightGeneration() throws InterruptedException, ExecutionException {
        setupValidIncidentAndRca();
        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.empty());

        String validLlmJson = """
                {
                  "headline": "Concurrent Headline",
                  "executiveSummary": "Concurrent Summary",
                  "observedSymptoms": ["Symptom 1"],
                  "rootCauseExplanation": "Root Cause 1",
                  "secondaryHypothesesEvaluated": [],
                  "historicalContextNarrative": null,
                  "suggestedInvestigationSteps": ["Step 1"],
                  "caveatsAndUncertainties": []
                }
                """;

        AtomicInteger providerCalls = new AtomicInteger(0);
        when(llmClientPort.generate(any(LlmPromptRequest.class))).thenAnswer(inv -> {
            providerCalls.incrementAndGet();
            Thread.sleep(50); // Simulate provider network latency
            return LlmGenerationResult.success(validLlmJson, validLlmJson, "openai-compatible", "gpt-4o-mini", 100, 50, 50L);
        });

        AtomicInteger dbSaves = new AtomicInteger(0);
        when(narrativeRepository.save(any(IncidentNarrativeEntity.class))).thenAnswer(inv -> {
            dbSaves.incrementAndGet();
            return inv.getArgument(0);
        });

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<IncidentNarrativeResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return service.generateOrGetNarrative(incidentId);
            }));
        }

        // Release all 20 threads simultaneously
        startLatch.countDown();

        List<IncidentNarrativeResponse> responses = new ArrayList<>();
        for (Future<IncidentNarrativeResponse> future : futures) {
            responses.add(future.get());
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // INVARIANT 1: At most 1 LLM provider call across all 20 concurrent requests
        assertThat(providerCalls.get()).isEqualTo(1);

        // INVARIANT 2: Exactly 1 database save
        assertThat(dbSaves.get()).isEqualTo(1);

        // INVARIANT 3: All 20 threads received the identical response
        assertThat(responses).hasSize(20);
        String firstHeadline = responses.get(0).headline();
        for (IncidentNarrativeResponse r : responses) {
            assertThat(r.headline()).isEqualTo(firstHeadline);
            assertThat(r.rcaAnalysisId()).isEqualTo(rcaAnalysisId);
        }
    }

    @Test
    @DisplayName("Single-Flight Concurrency: 20 concurrent requests when provider fails all receive fallback with 1 call")
    void testConcurrent20WaySingleFlightProviderFailure() throws InterruptedException, ExecutionException {
        setupValidIncidentAndRca();
        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.empty());

        AtomicInteger providerCalls = new AtomicInteger(0);
        when(llmClientPort.generate(any(LlmPromptRequest.class))).thenAnswer(inv -> {
            providerCalls.incrementAndGet();
            Thread.sleep(50);
            return LlmGenerationResult.failure("openai-compatible", "gpt-4o-mini", 50L, "Provider timeout");
        });

        when(narrativeRepository.save(any(IncidentNarrativeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<IncidentNarrativeResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return service.generateOrGetNarrative(incidentId);
            }));
        }

        startLatch.countDown();

        List<IncidentNarrativeResponse> responses = new ArrayList<>();
        for (Future<IncidentNarrativeResponse> future : futures) {
            responses.add(future.get());
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Exactly 1 provider call
        assertThat(providerCalls.get()).isEqualTo(1);

        // All 20 received deterministic fallback
        assertThat(responses).hasSize(20);
        for (IncidentNarrativeResponse r : responses) {
            assertThat(r.metadata().fallbackUsed()).isTrue();
            assertThat(r.headline()).contains("HIGH incident on api-gateway");
        }
    }

    @Test
    @DisplayName("Runbook Safety: LLM executable commands in suggested steps trigger deterministic fallback")
    void testUnsafeRunbookCommandsTriggersFallback() {
        setupValidIncidentAndRca();
        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.empty());

        // Model attempts to inject executable kubectl / rm commands in runbook
        String unsafeLlmJson = """
                {
                  "headline": "Malicious Runbook Headline",
                  "executiveSummary": "Summary",
                  "observedSymptoms": ["Symptom 1"],
                  "rootCauseExplanation": "Root cause explanation",
                  "secondaryHypothesesEvaluated": [],
                  "suggestedInvestigationSteps": [
                    "Check error rates in logs",
                    "kubectl delete pods --all -n production",
                    "rm -rf /var/log/*"
                  ],
                  "caveatsAndUncertainties": []
                }
                """;

        when(llmClientPort.generate(any(LlmPromptRequest.class)))
                .thenReturn(LlmGenerationResult.success(unsafeLlmJson, unsafeLlmJson, "openai-compatible", "gpt-4o-mini", 100, 50, 100L));
        when(narrativeRepository.save(any(IncidentNarrativeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IncidentNarrativeResponse response = service.generateOrGetNarrative(incidentId);

        // Must reject unsafe commands and fall back to deterministic safe checklist
        assertThat(response.metadata().fallbackUsed()).isTrue();
        assertThat(response.suggestedInvestigationSteps()).noneMatch(step -> step.contains("kubectl delete"));
        assertThat(response.suggestedInvestigationSteps()).noneMatch(step -> step.contains("rm -rf"));
        assertThat(response.suggestedInvestigationSteps()).anyMatch(step -> step.contains("Inspect telemetry metrics"));
    }

    @Test
    @DisplayName("Deterministic fallback activated on LLM provider failure")
    void testDeterministicFallbackOnProviderFailure() {
        setupValidIncidentAndRca();
        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.empty());

        when(llmClientPort.generate(any(LlmPromptRequest.class)))
                .thenReturn(LlmGenerationResult.failure("openai-compatible", "gpt-4o-mini", 5000L, "Provider timeout after 5000ms"));

        when(narrativeRepository.save(any(IncidentNarrativeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IncidentNarrativeResponse response = service.generateOrGetNarrative(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.metadata().fallbackUsed()).isTrue();
        assertThat(response.headline()).contains("HIGH incident on api-gateway");
        assertThat(response.executiveSummary()).contains("RCA analysis completed. Primary candidate root cause");
        assertThat(response.rootCauseExplanation()).contains("postgres-db");
        assertThat(response.suggestedInvestigationSteps()).isNotEmpty();
        assertThat(response.caveatsAndUncertainties()).contains("Synthesized via deterministic fallback generator due to LLM provider unavailability, timeout, or configuration.");

        ArgumentCaptor<IncidentNarrativeEntity> captor = ArgumentCaptor.forClass(IncidentNarrativeEntity.class);
        verify(narrativeRepository).save(captor.capture());
        assertThat(captor.getValue().getFallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("Deterministic fallback activated on malformed model output")
    void testDeterministicFallbackOnMalformedJson() {
        setupValidIncidentAndRca();
        when(narrativeRepository.findByRcaAnalysisId(rcaAnalysisId)).thenReturn(Optional.empty());

        when(llmClientPort.generate(any(LlmPromptRequest.class)))
                .thenReturn(LlmGenerationResult.success("I am not valid JSON!", "I am not valid JSON!", "openai-compatible", "gpt-4o-mini", 50, 10, 300L));

        when(narrativeRepository.save(any(IncidentNarrativeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IncidentNarrativeResponse response = service.generateOrGetNarrative(incidentId);

        assertThat(response.metadata().fallbackUsed()).isTrue();
        assertThat(response.headline()).contains("HIGH incident on api-gateway");
    }

    @Test
    @DisplayName("Throws ResourceNotFoundException when incident does not exist")
    void testMissingIncidentThrowsException() {
        UUID unknownId = UUID.randomUUID();
        when(incidentService.getIncidentById(unknownId))
                .thenThrow(new ResourceNotFoundException("Incident not found with ID: " + unknownId));

        assertThatThrownBy(() -> service.generateOrGetNarrative(unknownId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incident not found");
    }

    @Test
    @DisplayName("Throws ResourceNotFoundException when no RCA analysis exists for incident")
    void testMissingRcaThrowsException() {
        IncidentResponse incident = new IncidentResponse(
                incidentId, resourceId, "Title", "Desc", IncidentSeverity.MEDIUM,
                IncidentStatus.DETECTED, 0.5, null, Instant.now(), null, Instant.now(), Instant.now()
        );
        when(incidentService.getIncidentById(incidentId)).thenReturn(incident);
        when(rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)).thenReturn(null);

        assertThatThrownBy(() -> service.generateOrGetNarrative(incidentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No completed RCA analysis found");
    }

    @Test
    @DisplayName("getNarrative retrieves persisted narrative or throws ResourceNotFoundException")
    void testGetNarrative() {
        when(incidentService.getIncidentById(incidentId))
                .thenReturn(new IncidentResponse(incidentId, resourceId, "T", "D", IncidentSeverity.LOW, IncidentStatus.RESOLVED, 0.9, null, Instant.now(), Instant.now(), Instant.now(), Instant.now()));

        when(narrativeRepository.findTopByIncidentIdOrderByCreatedAtDesc(incidentId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getNarrative(incidentId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No narrative found");
    }
}
