package com.aurora.platform.incident.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceLockRegistryTest {

    @Test
    @DisplayName("Test A: Same-resource serialization - Maximum concurrent entries must be exactly 1")
    void testSameResourceSerialization() throws InterruptedException {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        UUID targetResource = UUID.randomUUID();
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger concurrentEntries = new AtomicInteger(0);
        AtomicInteger maxConcurrentEntries = new AtomicInteger(0);
        AtomicInteger totalExecutions = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    registry.executeWithLock(targetResource, () -> {
                        int current = concurrentEntries.incrementAndGet();
                        maxConcurrentEntries.accumulateAndGet(current, Math::max);

                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException ignored) {
                        }

                        concurrentEntries.decrementAndGet();
                        totalExecutions.incrementAndGet();
                        return null;
                    });
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(totalExecutions.get()).isEqualTo(threadCount);
        assertThat(maxConcurrentEntries.get())
                .as("Critical section must be serialized; max concurrent entries must be exactly 1")
                .isEqualTo(1);
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Test B: Different-resource concurrency - Unrelated resources execute concurrently")
    void testDifferentResourceConcurrency() throws InterruptedException {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        UUID resA = UUID.randomUUID();
        UUID resB = UUID.randomUUID();

        CountDownLatch threadAEntered = new CountDownLatch(1);
        CountDownLatch threadBEntered = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        Thread threadA = new Thread(() -> {
            registry.executeWithLock(resA, () -> {
                threadAEntered.countDown();
                try {
                    releaseLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return null;
            });
        });

        Thread threadB = new Thread(() -> {
            try {
                threadAEntered.await();
            } catch (InterruptedException ignored) {
            }
            registry.executeWithLock(resB, () -> {
                threadBEntered.countDown();
                return null;
            });
        });

        threadA.start();
        threadB.start();

        boolean bExecutedConcurrently = threadBEntered.await(3, TimeUnit.SECONDS);
        releaseLatch.countDown();
        threadA.join(2000);
        threadB.join(2000);

        assertThat(bExecutedConcurrently)
                .as("Thread B must acquire lock for resB without being blocked by held lock on resA")
                .isTrue();
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Test C: Eviction & Bounded Memory - Registry completely clears entries upon completion")
    void testEvictionAndBoundedMemory() {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            UUID id = UUID.randomUUID();
            registry.executeWithLock(id, () -> "done");
            assertThat(registry.hasLockForResource(id)).isFalse();
            assertThat(registry.getRefCountForResource(id)).isEqualTo(0);
        }

        assertThat(registry.getActiveLockCount())
                .as("Registry size must return to 0 after sequential UUID churn")
                .isEqualTo(0);
    }

    @RepeatedTest(25)
    @DisplayName("Test D: Adversarial acquire/release race interleaving - No split locks or dropped serialization")
    void testAdversarialAcquireReleaseInterleaving() throws Exception {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        UUID contestedResource = UUID.randomUUID();

        CyclicBarrier barrier = new CyclicBarrier(3);
        CountDownLatch threadAHolding = new CountDownLatch(1);
        CountDownLatch releaseThreadA = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(3);

        AtomicInteger activeInside = new AtomicInteger(0);
        AtomicInteger maxInside = new AtomicInteger(0);
        List<String> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());

        // Thread A: Acquires first, holds lock until signaled
        Thread threadA = new Thread(() -> {
            try {
                barrier.await();
                registry.executeWithLock(contestedResource, () -> {
                    int c = activeInside.incrementAndGet();
                    maxInside.accumulateAndGet(c, Math::max);
                    threadAHolding.countDown();
                    executionOrder.add("ThreadA");
                    try {
                        releaseThreadA.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    activeInside.decrementAndGet();
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread B: Contends immediately while Thread A holds
        Thread threadB = new Thread(() -> {
            try {
                barrier.await();
                threadAHolding.await();
                registry.executeWithLock(contestedResource, () -> {
                    int c = activeInside.incrementAndGet();
                    maxInside.accumulateAndGet(c, Math::max);
                    executionOrder.add("ThreadB");
                    activeInside.decrementAndGet();
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread C: Arrives right as Thread A is releasing
        Thread threadC = new Thread(() -> {
            try {
                barrier.await();
                threadAHolding.await();
                // Sleep briefly to interleave with A's release
                Thread.sleep(1);
                registry.executeWithLock(contestedResource, () -> {
                    int c = activeInside.incrementAndGet();
                    maxInside.accumulateAndGet(c, Math::max);
                    executionOrder.add("ThreadC");
                    activeInside.decrementAndGet();
                    return null;
                });
            } catch (Exception ignored) {
            } finally {
                doneLatch.countDown();
            }
        });

        threadA.start();
        threadB.start();
        threadC.start();

        // Let Thread A enter and confirm it is holding
        assertThat(threadAHolding.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.hasLockForResource(contestedResource)).isTrue();
        assertThat(registry.getRefCountForResource(contestedResource)).isGreaterThanOrEqualTo(1);

        // Now release Thread A
        releaseThreadA.countDown();

        boolean allDone = doneLatch.await(5, TimeUnit.SECONDS);
        threadA.join(1000);
        threadB.join(1000);
        threadC.join(1000);

        assertThat(allDone).isTrue();
        assertThat(maxInside.get())
                .as("At no point can two threads execute concurrently under the same resource lock")
                .isEqualTo(1);
        assertThat(executionOrder).hasSize(3);
        assertThat(executionOrder.get(0)).isEqualTo("ThreadA");
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
        assertThat(registry.hasLockForResource(contestedResource)).isFalse();
    }

    @Test
    @DisplayName("Test E: High-contention churn across multi-thread pool - Bounded size, zero deadlock, zero corruption")
    void testHighContentionChurn() throws Exception {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        int numThreads = 16;
        int operationsPerThread = 200;
        int numDistinctResources = 8;

        List<UUID> resources = new ArrayList<>();
        for (int i = 0; i < numDistinctResources; i++) {
            resources.add(UUID.randomUUID());
        }

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        AtomicInteger totalOps = new AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            int threadId = t;
            futures.add(executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < operationsPerThread; i++) {
                    UUID target = resources.get((threadId + i) % numDistinctResources);
                    registry.executeWithLock(target, () -> {
                        totalOps.incrementAndGet();
                        return null;
                    });
                }
                return null;
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(15, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(totalOps.get()).isEqualTo(numThreads * operationsPerThread);
        // Quiescence verification: Bounded registry returns to 0
        assertThat(registry.getActiveLockCount())
                .as("Registry must be completely empty after all threads finish")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Test F: Exception safety - Registry cleans up lock and ref count even if action throws")
    void testExceptionSafety() {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        UUID resourceId = UUID.randomUUID();

        assertThatThrownBy(() -> registry.executeWithLock(resourceId, () -> {
            throw new IllegalStateException("Simulated critical section failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Simulated critical section failure");

        // Lock must be released and evicted
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
        assertThat(registry.hasLockForResource(resourceId)).isFalse();
        assertThat(registry.getRefCountForResource(resourceId)).isEqualTo(0);

        // Subsequent acquisition for same resource must succeed seamlessly
        String result = registry.executeWithLock(resourceId, () -> "subsequent-success");
        assertThat(result).isEqualTo("subsequent-success");
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Null Resource ID passes through without locking")
    void testNullResourceIdPassesThrough() {
        ResourceLockRegistry registry = new ResourceLockRegistry();
        String result = registry.executeWithLock(null, () -> "null-resource-ok");
        assertThat(result).isEqualTo("null-resource-ok");
        assertThat(registry.getActiveLockCount()).isEqualTo(0);
    }
}
