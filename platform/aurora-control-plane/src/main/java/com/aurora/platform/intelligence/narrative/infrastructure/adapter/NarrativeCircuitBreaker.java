package com.aurora.platform.intelligence.narrative.infrastructure.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe circuit breaker protecting the control plane from external LLM failure storms.
 * Enforces the exact thresholds specified in ADR-007 Section 12:
 * <ul>
 *   <li>Trips to {@code OPEN} after 3 consecutive failures or timeouts.</li>
 *   <li>Remains {@code OPEN} for 60 seconds before transitioning to {@code HALF_OPEN}.</li>
 *   <li>Permits exactly ONE probe execution during {@code HALF_OPEN}; concurrent callers are rejected.</li>
 *   <li>Failed probe immediately returns to {@code OPEN}.</li>
 *   <li>Successful probe resets to {@code CLOSED}.</li>
 * </ul>
 */
public class NarrativeCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(NarrativeCircuitBreaker.class);

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openDurationMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastTrippedTimestamp = new AtomicLong(0);
    private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean(false);

    public NarrativeCircuitBreaker(int failureThreshold, int openDurationSeconds) {
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDurationSeconds * 1000L;
    }

    /**
     * Determines whether an execution is permitted under current circuit breaker state.
     *
     * @return true if permitted; false if circuit is OPEN or another probe is in flight
     */
    public boolean allowExecution() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }

        long now = System.currentTimeMillis();
        long trippedAt = lastTrippedTimestamp.get();

        if (current == State.OPEN) {
            if (now - trippedAt >= openDurationMillis) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenProbeInFlight.set(true);
                    log.info("Circuit breaker transitioning from OPEN to HALF_OPEN probe");
                    return true;
                }
            }
            return false;
        }

        if (current == State.HALF_OPEN) {
            // Exactly one probe permitted; concurrent callers are rejected
            return halfOpenProbeInFlight.compareAndSet(false, true);
        }

        return false;
    }

    /**
     * Records a successful execution, resetting failure counters and closing the circuit.
     */
    public void recordSuccess() {
        halfOpenProbeInFlight.set(false);
        int previousFailures = consecutiveFailures.getAndSet(0);
        State previousState = state.getAndSet(State.CLOSED);
        if (previousState != State.CLOSED || previousFailures > 0) {
            log.info("Circuit breaker reset to CLOSED after successful execution");
        }
    }

    /**
     * Records an execution failure (timeout, network error, 5xx, or invalid response).
     */
    public void recordFailure() {
        halfOpenProbeInFlight.set(false);
        State current = state.get();

        if (current == State.HALF_OPEN) {
            // Failed probe in HALF_OPEN immediately returns to OPEN
            lastTrippedTimestamp.set(System.currentTimeMillis());
            state.set(State.OPEN);
            consecutiveFailures.set(failureThreshold);
            log.warn("Circuit breaker probe failed in HALF_OPEN; returning to OPEN");
            return;
        }

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            lastTrippedTimestamp.set(System.currentTimeMillis());
            state.set(State.OPEN);
            log.warn("Circuit breaker tripped to OPEN after {} consecutive failures", failures);
        }
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public void reset() {
        consecutiveFailures.set(0);
        state.set(State.CLOSED);
        lastTrippedTimestamp.set(0);
        halfOpenProbeInFlight.set(false);
    }
}
