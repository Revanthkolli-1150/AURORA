package com.aurora.platform.incident.service;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JVM-local, bounded lock registry providing per-resource serialization.
 *
 * <p><strong>Architectural Contract:</strong>
 * <ul>
 *   <li><strong>Current Guarantee:</strong> Single-JVM serialization per resource ID.
 *       Two threads correlating anomalies for the same resource ID are strictly serialized,
 *       preventing duplicate active incident creation.</li>
 *   <li><strong>Non-Contention Concurrency:</strong> Correlation operations on different resource IDs
 *       proceed concurrently without blocking each other.</li>
 *   <li><strong>Bounded Memory Management:</strong> Lock objects are reference-counted.
 *       When all active and waiting threads for a given resource release the lock, the lock entry
 *       is automatically evicted from the registry, preventing unbounded heap growth.</li>
 *   <li><strong>Known Limitation:</strong> This registry provides JVM-local synchronization only.
 *       Multi-instance distributed locking is explicitly deferred to future multi-node deployment.</li>
 * </ul>
 */
@Component
public class ResourceLockRegistry {

    static class RefCountedLock {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        final AtomicInteger refCount = new AtomicInteger(1);
    }

    private final ConcurrentHashMap<UUID, RefCountedLock> lockMap = new ConcurrentHashMap<>();

    /**
     * Executes the given action holding the serialized lock for the specified resource.
     *
     * @param resourceId the resource ID to serialize on
     * @param action     the supplier action to execute
     * @param <T>        the return type
     * @return the result of the action
     */
    public <T> T executeWithLock(UUID resourceId, Supplier<T> action) {
        if (resourceId == null) {
            return action.get();
        }

        RefCountedLock refLock = lockMap.compute(resourceId, (id, existing) -> {
            if (existing == null) {
                return new RefCountedLock();
            }
            existing.refCount.incrementAndGet();
            return existing;
        });

        refLock.lock.lock();
        try {
            return action.get();
        } finally {
            refLock.lock.unlock();
            lockMap.computeIfPresent(resourceId, (id, existing) -> {
                if (existing == refLock && existing.refCount.decrementAndGet() <= 0) {
                    return null; // Evict from map when no threads are waiting or holding
                }
                return existing;
            });
        }
    }

    /**
     * Returns the current number of actively held or contended resource locks in the registry.
     * Useful for diagnostic and test verification.
     */
    public int getActiveLockCount() {
        return lockMap.size();
    }

    /**
     * Checks if a lock entry currently exists in the registry for the given resource ID.
     */
    public boolean hasLockForResource(UUID resourceId) {
        return lockMap.containsKey(resourceId);
    }

    /**
     * Returns the current reference count for a resource ID, or 0 if not present in the registry.
     */
    public int getRefCountForResource(UUID resourceId) {
        RefCountedLock lock = lockMap.get(resourceId);
        return lock != null ? lock.refCount.get() : 0;
    }
}
