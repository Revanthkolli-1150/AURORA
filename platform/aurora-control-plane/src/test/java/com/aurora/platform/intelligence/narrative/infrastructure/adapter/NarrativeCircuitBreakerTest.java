package com.aurora.platform.intelligence.narrative.infrastructure.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativeCircuitBreakerTest {

    @Test
    @DisplayName("Initial state is CLOSED and allows execution")
    void testInitialStateClosed() {
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(3, 60);

        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
        assertThat(cb.allowExecution()).isTrue();
        assertThat(cb.getConsecutiveFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("Circuit breaker trips to OPEN after reaching failure threshold")
    void testTripsToOpenAfterThreshold() {
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(3, 60);

        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
        assertThat(cb.allowExecution()).isTrue();

        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
        assertThat(cb.allowExecution()).isTrue();

        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);
        assertThat(cb.allowExecution()).isFalse();
    }

    @Test
    @DisplayName("Success resets failure count in CLOSED state")
    void testSuccessResetsFailureCount() {
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(3, 60);

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getConsecutiveFailures()).isEqualTo(2);

        cb.recordSuccess();
        assertThat(cb.getConsecutiveFailures()).isEqualTo(0);
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Transitions to HALF_OPEN after timeout and resets to CLOSED on probe success")
    void testHalfOpenProbeSuccess() throws InterruptedException {
        // Use a short open duration (1 second) for test
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(2, 1);

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);
        assertThat(cb.allowExecution()).isFalse();

        // Wait for cooldown to elapse
        Thread.sleep(1100);

        // First call after cooldown should transition to HALF_OPEN and allow trial probe
        assertThat(cb.allowExecution()).isTrue();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.HALF_OPEN);

        // Probe succeeds
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
        assertThat(cb.getConsecutiveFailures()).isEqualTo(0);
    }

    @Test
    @DisplayName("Failed probe in HALF_OPEN immediately trips back to OPEN")
    void testFailedProbeInHalfOpenReturnsImmediatelyToOpen() throws InterruptedException {
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(2, 1);

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);

        Thread.sleep(1100);

        assertThat(cb.allowExecution()).isTrue();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.HALF_OPEN);

        // Probe fails
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);
        assertThat(cb.allowExecution()).isFalse();
    }

    @Test
    @DisplayName("Concurrency: Concurrent callers in HALF_OPEN allow exactly ONE probe")
    void testConcurrentHalfOpenAllowsExactlyOneProbe() throws InterruptedException, ExecutionException {
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(2, 1);

        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);

        Thread.sleep(1100);

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return cb.allowExecution();
            }));
        }

        startLatch.countDown();

        AtomicInteger allowedCount = new AtomicInteger(0);
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                allowedCount.incrementAndGet();
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // INVARIANT: Exactly ONE probe allowed concurrently in HALF_OPEN
        assertThat(allowedCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Reset explicitly restores CLOSED state")
    void testExplicitReset() {
        NarrativeCircuitBreaker cb = new NarrativeCircuitBreaker(2, 60);
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.OPEN);

        cb.reset();
        assertThat(cb.getState()).isEqualTo(NarrativeCircuitBreaker.State.CLOSED);
        assertThat(cb.getConsecutiveFailures()).isEqualTo(0);
        assertThat(cb.allowExecution()).isTrue();
    }
}
