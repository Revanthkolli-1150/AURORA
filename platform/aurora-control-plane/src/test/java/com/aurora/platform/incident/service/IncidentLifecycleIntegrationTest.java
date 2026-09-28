package com.aurora.platform.incident.service;

import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IncidentLifecycleIntegrationTest {

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ResourceService resourceService;

    private UUID resourceId;

    @BeforeEach
    void setUp() {
        ResourceResponse res = resourceService.createResource(new CreateResourceRequest(
                "lifecycle-test-res-" + UUID.randomUUID(),
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "test-env",
                "test-host",
                Map.of()
        ));
        resourceId = res.id();
    }

    private IncidentResponse createTestIncident() {
        return incidentService.createIncident(new CreateIncidentRequest(
                resourceId,
                "Lifecycle Test Incident",
                "Testing lifecycle state machine",
                IncidentSeverity.HIGH,
                IncidentStatus.DETECTED,
                0.9,
                null,
                Instant.now()
        ));
    }

    @Test
    @DisplayName("Legal Full Happy Path: DETECTED -> INVESTIGATING -> DIAGNOSED -> RECOVERING -> VERIFYING -> RESOLVED")
    void testFullHappyPathPersistence() {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();
        assertThat(incident.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(incident.resolvedAt()).isNull();

        // 1. DETECTED -> INVESTIGATING
        IncidentResponse s1 = incidentService.updateIncidentStatus(id, IncidentStatus.INVESTIGATING);
        assertThat(s1.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(incidentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);

        // 2. INVESTIGATING -> DIAGNOSED
        IncidentResponse s2 = incidentService.updateIncidentStatus(id, IncidentStatus.DIAGNOSED);
        assertThat(s2.status()).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(incidentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.DIAGNOSED);

        // 3. DIAGNOSED -> RECOVERING
        IncidentResponse s3 = incidentService.updateIncidentStatus(id, IncidentStatus.RECOVERING);
        assertThat(s3.status()).isEqualTo(IncidentStatus.RECOVERING);
        assertThat(incidentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.RECOVERING);

        // 4. RECOVERING -> VERIFYING
        IncidentResponse s4 = incidentService.updateIncidentStatus(id, IncidentStatus.VERIFYING);
        assertThat(s4.status()).isEqualTo(IncidentStatus.VERIFYING);
        assertThat(incidentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.VERIFYING);

        // 5. VERIFYING -> RESOLVED
        IncidentResponse s5 = incidentService.updateIncidentStatus(id, IncidentStatus.RESOLVED);
        assertThat(s5.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(s5.resolvedAt()).isNotNull();

        IncidentEntity inDb = incidentRepository.findById(id).orElseThrow();
        assertThat(inDb.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(inDb.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("Terminal State: RESOLVED cannot transition to any other status and database remains RESOLVED")
    void testResolvedTerminalCannotTransition() {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();

        incidentService.updateIncidentStatus(id, IncidentStatus.INVESTIGATING);
        incidentService.updateIncidentStatus(id, IncidentStatus.DIAGNOSED);
        incidentService.updateIncidentStatus(id, IncidentStatus.RECOVERING);
        incidentService.updateIncidentStatus(id, IncidentStatus.VERIFYING);
        incidentService.updateIncidentStatus(id, IncidentStatus.RESOLVED);

        for (IncidentStatus target : IncidentStatus.values()) {
            if (target != IncidentStatus.RESOLVED) {
                assertThatThrownBy(() -> incidentService.updateIncidentStatus(id, target))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Illegal incident lifecycle transition");

                // Verify persistence is unchanged
                assertThat(incidentRepository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(IncidentStatus.RESOLVED);
            }
        }
    }

    @Test
    @DisplayName("Terminal State: FAILED cannot transition to any other status and database remains FAILED")
    void testFailedTerminalCannotTransition() {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();

        // Direct transition from DETECTED to FAILED
        incidentService.updateIncidentStatus(id, IncidentStatus.FAILED);
        assertThat(incidentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(IncidentStatus.FAILED);

        for (IncidentStatus target : IncidentStatus.values()) {
            if (target != IncidentStatus.FAILED) {
                assertThatThrownBy(() -> incidentService.updateIncidentStatus(id, target))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Illegal incident lifecycle transition");

                // Verify persistence is unchanged
                assertThat(incidentRepository.findById(id).orElseThrow().getStatus())
                        .isEqualTo(IncidentStatus.FAILED);
            }
        }
    }

    @Test
    @DisplayName("Illegal Transition: DETECTED directly to RESOLVED is rejected and state remains DETECTED")
    void testIllegalSkipTransitionRejectedAndRollsBack() {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();

        assertThatThrownBy(() -> incidentService.updateIncidentStatus(id, IncidentStatus.RESOLVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal incident lifecycle transition");

        assertThat(incidentRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.DETECTED);
    }

    @Test
    @DisplayName("Idempotent Transition: S -> S is accepted without error")
    void testIdempotentSelfTransition() {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();

        incidentService.updateIncidentStatus(id, IncidentStatus.INVESTIGATING);
        IncidentResponse self = incidentService.updateIncidentStatus(id, IncidentStatus.INVESTIGATING);

        assertThat(self.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(incidentRepository.findById(id).orElseThrow().getStatus())
                .isEqualTo(IncidentStatus.INVESTIGATING);
    }

    @Test
    @DisplayName("Concurrency: Concurrent conflicting status updates serialize safely via pessimistic lock")
    void testConcurrentStatusUpdatesSerializeSafely() throws InterruptedException {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);

        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger illegalTransitions = new AtomicInteger(0);

        // Thread 0: DETECTED -> INVESTIGATING (Legal)
        executor.submit(() -> {
            try {
                startLatch.await();
                incidentService.updateIncidentStatus(id, IncidentStatus.INVESTIGATING);
                successes.incrementAndGet();
            } catch (IllegalStateException e) {
                illegalTransitions.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 1: DETECTED -> DIAGNOSED (Illegal from DETECTED)
        executor.submit(() -> {
            try {
                startLatch.await();
                incidentService.updateIncidentStatus(id, IncidentStatus.DIAGNOSED);
                successes.incrementAndGet();
            } catch (IllegalStateException e) {
                illegalTransitions.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: DETECTED -> RECOVERING (Illegal from DETECTED)
        executor.submit(() -> {
            try {
                startLatch.await();
                incidentService.updateIncidentStatus(id, IncidentStatus.RECOVERING);
                successes.incrementAndGet();
            } catch (IllegalStateException e) {
                illegalTransitions.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 3: DETECTED -> RESOLVED (Illegal from DETECTED)
        executor.submit(() -> {
            try {
                startLatch.await();
                incidentService.updateIncidentStatus(id, IncidentStatus.RESOLVED);
                successes.incrementAndGet();
            } catch (IllegalStateException e) {
                illegalTransitions.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // The final state in the database must be a legal progression state
        IncidentEntity finalEntity = incidentRepository.findById(id).orElseThrow();
        assertThat(finalEntity.getStatus()).isIn(
                IncidentStatus.INVESTIGATING,
                IncidentStatus.DIAGNOSED,
                IncidentStatus.RECOVERING
        );
        // Illegal jump directly from RECOVERING to RESOLVED must be rejected
        assertThat(illegalTransitions.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Concurrency: Two threads racing mutually exclusive transitions (INVESTIGATING vs FAILED) from DETECTED")
    void testMutuallyExclusiveConcurrentTransitions() throws InterruptedException {
        IncidentResponse incident = createTestIncident();
        UUID id = incident.id();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger toInvestigating = new AtomicInteger(0);
        AtomicInteger toFailed = new AtomicInteger(0);
        AtomicInteger exceptions = new AtomicInteger(0);

        // Thread 1: DETECTED -> INVESTIGATING
        executor.submit(() -> {
            try {
                startLatch.await();
                incidentService.updateIncidentStatus(id, IncidentStatus.INVESTIGATING);
                toInvestigating.incrementAndGet();
            } catch (Exception e) {
                exceptions.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2: DETECTED -> FAILED
        executor.submit(() -> {
            try {
                startLatch.await();
                incidentService.updateIncidentStatus(id, IncidentStatus.FAILED);
                toFailed.incrementAndGet();
            } catch (Exception e) {
                exceptions.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        IncidentEntity finalEntity = incidentRepository.findById(id).orElseThrow();
        // One of the two transitions succeeded; if INVESTIGATING succeeded first, then FAILED also succeeds (since INVESTIGATING -> FAILED is legal).
        // But if FAILED succeeded first, INVESTIGATING is terminal-rejected (since FAILED is terminal)!
        if (finalEntity.getStatus() == IncidentStatus.FAILED) {
            assertThat(toFailed.get()).isEqualTo(1);
        } else {
            assertThat(finalEntity.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
            assertThat(toInvestigating.get()).isEqualTo(1);
        }
    }
}
