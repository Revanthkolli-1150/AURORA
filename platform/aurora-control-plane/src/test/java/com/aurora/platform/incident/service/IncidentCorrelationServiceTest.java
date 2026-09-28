package com.aurora.platform.incident.service;

import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.incident.config.IncidentCorrelationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentCorrelationServiceTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentAnomalyEvidenceRepository evidenceRepository;

    @Captor
    private ArgumentCaptor<IncidentEntity> incidentCaptor;

    @Captor
    private ArgumentCaptor<IncidentAnomalyEvidenceEntity> evidenceCaptor;

    private IncidentCorrelationProperties properties;
    private IncidentCorrelationServiceImpl correlationService;

    private UUID resourceId;
    private Instant now;

    @BeforeEach
    void setUp() {
        properties = new IncidentCorrelationProperties();
        properties.setCorrelationWindowSeconds(300L); // 5 minutes default

        correlationService = new IncidentCorrelationServiceImpl(
                incidentRepository,
                evidenceRepository,
                properties
        );

        resourceId = UUID.randomUUID();
        now = Instant.now();
    }

    private AnomalyDetectionResponse createAnomaly(
            UUID resId, String metric, Double value, AnomalyStatus status, Double anomalyScore, Double zScore, Instant time) {
        return new AnomalyDetectionResponse(
                resId,
                metric,
                value,
                "Z_SCORE",
                status,
                anomalyScore,
                zScore,
                3.0,
                15,
                45.0,
                2.0,
                40.0,
                50.0,
                time
        );
    }

    @Test
    @DisplayName("1. Anomalous result creates new incident")
    void shouldCreateNewIncidentForAnomalousResult() {
        AnomalyDetectionResponse anomaly = createAnomaly(
                resourceId, "cpu_usage", 95.0, AnomalyStatus.ANOMALOUS, 1.0, 25.0, now);

        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceId), any()))
                .thenReturn(Collections.emptyList());

        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> {
            IncidentEntity entity = invocation.getArgument(0);
            entity.setId(incidentId);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            return entity;
        });

        IncidentResponse response = correlationService.correlateAnomaly(anomaly);

        assertThat(response).isNotNull();
        assertThat(response.resourceId()).isEqualTo(resourceId);
        assertThat(response.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(response.rootCause()).isNull();
        assertThat(response.confidence()).isNull();

        verify(incidentRepository).save(incidentCaptor.capture());
        IncidentEntity saved = incidentCaptor.getValue();
        assertThat(saved.getTitle()).contains("cpu usage anomaly detected");
        assertThat(saved.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(saved.getStatus()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(saved.getRootCause()).isNull();
        assertThat(saved.getConfidence()).isNull();

        verify(evidenceRepository).save(evidenceCaptor.capture());
        IncidentAnomalyEvidenceEntity evidence = evidenceCaptor.getValue();
        assertThat(evidence.getIncidentId()).isEqualTo(incidentId);
        assertThat(evidence.getResourceId()).isEqualTo(resourceId);
        assertThat(evidence.getMetricName()).isEqualTo("cpu_usage");
        assertThat(evidence.getObservedValue()).isEqualTo(95.0);
        assertThat(evidence.getAnomalyScore()).isEqualTo(1.0);
        assertThat(evidence.getZScore()).isEqualTo(25.0);
    }

    @Test
    @DisplayName("2. Normal result creates no incident")
    void shouldNotCreateIncidentForNormalResult() {
        AnomalyDetectionResponse normal = createAnomaly(
                resourceId, "cpu_usage", 45.0, AnomalyStatus.NORMAL, 0.0, 0.0, now);

        IncidentResponse response = correlationService.correlateAnomaly(normal);

        assertThat(response).isNull();
        verify(incidentRepository, never()).save(any());
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("3. Insufficient data result creates no incident")
    void shouldNotCreateIncidentForInsufficientData() {
        AnomalyDetectionResponse insufficient = createAnomaly(
                resourceId, "disk_io", 100.0, AnomalyStatus.INSUFFICIENT_DATA, null, null, now);

        IncidentResponse response = correlationService.correlateAnomaly(insufficient);

        assertThat(response).isNull();
        verify(incidentRepository, never()).save(any());
        verify(evidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("4. Second anomaly on same resource within correlation window attaches to existing incident")
    void shouldAttachToExistingIncidentWithinCorrelationWindow() {
        UUID existingIncidentId = UUID.randomUUID();
        IncidentEntity existingIncident = IncidentEntity.builder()
                .id(existingIncidentId)
                .resourceId(resourceId)
                .title("cpu usage anomaly detected")
                .description("Initial description")
                .severity(IncidentSeverity.MEDIUM)
                .status(IncidentStatus.DETECTED)
                .detectedAt(now.minus(2, ChronoUnit.MINUTES))
                .createdAt(now.minus(2, ChronoUnit.MINUTES))
                .updatedAt(now.minus(2, ChronoUnit.MINUTES))
                .build();

        IncidentAnomalyEvidenceEntity initialEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .incidentId(existingIncidentId)
                .resourceId(resourceId)
                .metricName("cpu_usage")
                .observedValue(70.0)
                .anomalyScore(0.6)
                .observedAt(now.minus(2, ChronoUnit.MINUTES))
                .build();

        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceId), any()))
                .thenReturn(List.of(existingIncident));
        when(evidenceRepository.findFirstByIncidentIdOrderByObservedAtDesc(existingIncidentId))
                .thenReturn(Optional.of(initialEvidence));
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Second anomaly 2 minutes later (within 300s window), with higher score 0.98 (CRITICAL)
        AnomalyDetectionResponse secondAnomaly = createAnomaly(
                resourceId, "memory_usage", 99.0, AnomalyStatus.ANOMALOUS, 0.98, 4.5, now);

        IncidentResponse response = correlationService.correlateAnomaly(secondAnomaly);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(existingIncidentId);
        // Escalated severity
        assertThat(response.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        // Updated title for multiple anomalies
        assertThat(response.title()).isEqualTo("Multiple reliability anomalies detected");

        verify(evidenceRepository).save(evidenceCaptor.capture());
        IncidentAnomalyEvidenceEntity savedEvidence = evidenceCaptor.getValue();
        assertThat(savedEvidence.getIncidentId()).isEqualTo(existingIncidentId);
        assertThat(savedEvidence.getMetricName()).isEqualTo("memory_usage");
        assertThat(savedEvidence.getObservedValue()).isEqualTo(99.0);
    }

    @Test
    @DisplayName("5. Anomaly outside correlation window creates new incident")
    void shouldCreateNewIncidentWhenAnomalyOutsideCorrelationWindow() {
        UUID oldIncidentId = UUID.randomUUID();
        IncidentEntity oldIncident = IncidentEntity.builder()
                .id(oldIncidentId)
                .resourceId(resourceId)
                .title("cpu usage anomaly detected")
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.DETECTED)
                .detectedAt(now.minus(15, ChronoUnit.MINUTES))
                .createdAt(now.minus(15, ChronoUnit.MINUTES))
                .updatedAt(now.minus(15, ChronoUnit.MINUTES))
                .build();

        IncidentAnomalyEvidenceEntity oldEvidence = IncidentAnomalyEvidenceEntity.builder()
                .id(UUID.randomUUID())
                .incidentId(oldIncidentId)
                .resourceId(resourceId)
                .metricName("cpu_usage")
                .observedValue(85.0)
                .anomalyScore(0.85)
                .observedAt(now.minus(15, ChronoUnit.MINUTES)) // 15 mins ago (> 300s)
                .build();

        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceId), any()))
                .thenReturn(List.of(oldIncident));
        when(evidenceRepository.findFirstByIncidentIdOrderByObservedAtDesc(oldIncidentId))
                .thenReturn(Optional.of(oldEvidence));

        UUID newIncidentId = UUID.randomUUID();
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> {
            IncidentEntity entity = invocation.getArgument(0);
            entity.setId(newIncidentId);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            return entity;
        });

        AnomalyDetectionResponse newAnomaly = createAnomaly(
                resourceId, "cpu_usage", 92.0, AnomalyStatus.ANOMALOUS, 0.9, 3.8, now);

        IncidentResponse response = correlationService.correlateAnomaly(newAnomaly);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(newIncidentId);

        verify(evidenceRepository).save(evidenceCaptor.capture());
        assertThat(evidenceCaptor.getValue().getIncidentId()).isEqualTo(newIncidentId);
    }

    @Test
    @DisplayName("6. Resolved incident does not receive new evidence")
    void shouldNotAttachToResolvedIncident() {
        // Resolved incident is not in active statuses query, so repository returns empty list
        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceId), any()))
                .thenReturn(Collections.emptyList());

        UUID newIncidentId = UUID.randomUUID();
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> {
            IncidentEntity entity = invocation.getArgument(0);
            entity.setId(newIncidentId);
            return entity;
        });

        AnomalyDetectionResponse anomaly = createAnomaly(
                resourceId, "cpu_usage", 95.0, AnomalyStatus.ANOMALOUS, 0.96, 4.0, now);

        IncidentResponse response = correlationService.correlateAnomaly(anomaly);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(newIncidentId);
        assertThat(response.status()).isEqualTo(IncidentStatus.DETECTED);
    }

    @Test
    @DisplayName("7. Failed incident does not receive new evidence")
    void shouldNotAttachToFailedIncident() {
        // Failed incident is not returned by active statuses query
        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceId), any()))
                .thenReturn(Collections.emptyList());

        UUID newIncidentId = UUID.randomUUID();
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> {
            IncidentEntity entity = invocation.getArgument(0);
            entity.setId(newIncidentId);
            return entity;
        });

        AnomalyDetectionResponse anomaly = createAnomaly(
                resourceId, "disk_io", 999.0, AnomalyStatus.ANOMALOUS, 0.97, 5.0, now);

        IncidentResponse response = correlationService.correlateAnomaly(anomaly);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(newIncidentId);
    }

    @Test
    @DisplayName("8. Different resource creates separate incident")
    void shouldCreateSeparateIncidentForDifferentResource() {
        UUID resourceB = UUID.randomUUID();

        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceB), any()))
                .thenReturn(Collections.emptyList());

        UUID incidentIdB = UUID.randomUUID();
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> {
            IncidentEntity entity = invocation.getArgument(0);
            entity.setId(incidentIdB);
            return entity;
        });

        AnomalyDetectionResponse anomalyB = createAnomaly(
                resourceB, "cpu_usage", 98.0, AnomalyStatus.ANOMALOUS, 1.0, 6.0, now);

        IncidentResponse response = correlationService.correlateAnomaly(anomalyB);

        assertThat(response).isNotNull();
        assertThat(response.resourceId()).isEqualTo(resourceB);
        assertThat(response.id()).isEqualTo(incidentIdB);
    }

    @Test
    @DisplayName("10. Root cause remains null and 11. Status starts as DETECTED")
    void rootCauseRemainsNullAndStatusStartsDetected() {
        when(incidentRepository.findByResourceIdAndStatusInOrderByDetectedAtDesc(eq(resourceId), any()))
                .thenReturn(Collections.emptyList());
        when(incidentRepository.save(any(IncidentEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AnomalyDetectionResponse anomaly = createAnomaly(
                resourceId, "latency", 500.0, AnomalyStatus.ANOMALOUS, 0.88, 3.2, now);

        IncidentResponse response = correlationService.correlateAnomaly(anomaly);

        assertThat(response.rootCause()).isNull();
        assertThat(response.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(response.confidence()).isNull();
    }

    @Test
    @DisplayName("12. Severity mapping: verifies LOW, MEDIUM, HIGH, CRITICAL bands")
    void verifySeverityMappingBands() {
        assertThat(correlationService.mapSeverity(0.3)).isEqualTo(IncidentSeverity.LOW);
        assertThat(correlationService.mapSeverity(0.5)).isEqualTo(IncidentSeverity.MEDIUM);
        assertThat(correlationService.mapSeverity(0.75)).isEqualTo(IncidentSeverity.MEDIUM);
        assertThat(correlationService.mapSeverity(0.8)).isEqualTo(IncidentSeverity.HIGH);
        assertThat(correlationService.mapSeverity(0.94)).isEqualTo(IncidentSeverity.HIGH);
        assertThat(correlationService.mapSeverity(0.95)).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(correlationService.mapSeverity(1.0)).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(correlationService.mapSeverity(null)).isEqualTo(IncidentSeverity.LOW);
    }

    @Test
    @DisplayName("13. Concurrency: ResourceLockRegistry serializes executions on identical resource and cleans up")
    void verifyResourceLockRegistrySerializesAndCleansUp() throws InterruptedException {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        UUID targetResourceId = UUID.randomUUID();
        int threadCount = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger executionCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    registry.executeWithLock(targetResourceId, () -> {
                        executionCounter.incrementAndGet();
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {}
                        return null;
                    });
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(executionCounter.get()).isEqualTo(threadCount);
        // Bounded lock registry verification: all entries evicted once execution completes
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("14. Concurrency: Different resources proceed without blocking")
    void verifyDifferentResourcesProceedConcurrently() throws InterruptedException {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        UUID resA = UUID.randomUUID();
        UUID resB = UUID.randomUUID();

        java.util.concurrent.CountDownLatch latchAEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch latchBEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseA = new java.util.concurrent.CountDownLatch(1);

        Thread threadA = new Thread(() -> {
            registry.executeWithLock(resA, () -> {
                latchAEntered.countDown();
                try {
                    releaseA.await(3, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return null;
            });
        });

        Thread threadB = new Thread(() -> {
            try {
                latchAEntered.await();
            } catch (InterruptedException ignored) {}
            // Thread B must be able to acquire lock for resB immediately even while resA lock is held
            registry.executeWithLock(resB, () -> {
                latchBEntered.countDown();
                return null;
            });
        });

        threadA.start();
        threadB.start();

        boolean bAcquired = latchBEntered.await(2, java.util.concurrent.TimeUnit.SECONDS);
        releaseA.countDown();
        threadA.join(2000);
        threadB.join(2000);

        assertThat(bAcquired).as("Thread B must acquire lock for resB concurrently while resA is held").isTrue();
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
    }
}
