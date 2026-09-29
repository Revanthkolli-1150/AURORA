package com.aurora.platform.recovery.infrastructure.actuator;

import com.aurora.platform.recovery.application.port.out.ActuationRequest;
import com.aurora.platform.recovery.application.port.out.ActuationResult;
import com.aurora.platform.recovery.application.port.out.ActuationStatus;
import com.aurora.platform.recovery.application.port.out.ActuationStatusResult;
import com.aurora.platform.recovery.application.port.out.RecoveryActuatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory sandbox simulator for Phase 5A controlled actuation foundation.
 * <p>
 * This simulator executes strictly in-memory without invoking any shell commands,
 * host processes, cloud APIs, or Kubernetes clusters.
 */
@Component
public class SimulatedSandboxActuator implements RecoveryActuatorPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatedSandboxActuator.class);

    public enum SimulationBehavior {
        SUCCESS,
        FAILURE,
        TIMEOUT
    }

    private volatile SimulationBehavior defaultBehavior = SimulationBehavior.SUCCESS;
    private final Map<String, SimulationBehavior> keyedBehaviors = new ConcurrentHashMap<>();
    private final List<ActuationRequest> recordedInvocations = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, AtomicInteger> executionCountsByKey = new ConcurrentHashMap<>();

    @Override
    public ActuationResult execute(ActuationRequest request) {
        log.info("SimulatedSandboxActuator executing action {} on target {} (idempotencyKey={})",
                request.actionType(), request.targetResourceName(), request.idempotencyKey());

        recordedInvocations.add(request);
        executionCountsByKey.computeIfAbsent(request.idempotencyKey(), k -> new AtomicInteger(0)).incrementAndGet();

        SimulationBehavior behavior = keyedBehaviors.getOrDefault(request.idempotencyKey(), defaultBehavior);
        String externalRef = "sandbox-exec-" + UUID.randomUUID();

        return switch (behavior) {
            case SUCCESS -> ActuationResult.success(
                    externalRef,
                    "Simulated sandbox actuation succeeded for " + request.actionType(),
                    "{\"sandboxStatus\":\"COMPLETED\",\"target\":\"" + request.targetResourceName() + "\"}"
            );
            case FAILURE -> ActuationResult.failure(
                    externalRef,
                    "Simulated sandbox actuation intentionally failed for " + request.actionType(),
                    "{\"sandboxStatus\":\"FAILED\",\"error\":\"SIMULATED_FAILURE\"}"
            );
            case TIMEOUT -> ActuationResult.timeout(
                    externalRef,
                    "Simulated sandbox actuation timed out after " + request.timeout().toSeconds() + "s"
            );
        };
    }

    @Override
    public ActuationStatusResult queryStatus(String externalExecutionReference) {
        return new ActuationStatusResult(ActuationStatus.SUCCESS, "Sandbox execution query completed");
    }

    public void setDefaultBehavior(SimulationBehavior defaultBehavior) {
        this.defaultBehavior = defaultBehavior;
    }

    public void setBehaviorForIdempotencyKey(String idempotencyKey, SimulationBehavior behavior) {
        this.keyedBehaviors.put(idempotencyKey, behavior);
    }

    public List<ActuationRequest> getRecordedInvocations() {
        return Collections.unmodifiableList(new ArrayList<>(recordedInvocations));
    }

    public int getExecutionCountForIdempotencyKey(String idempotencyKey) {
        AtomicInteger count = executionCountsByKey.get(idempotencyKey);
        return count != null ? count.get() : 0;
    }

    public void reset() {
        this.defaultBehavior = SimulationBehavior.SUCCESS;
        this.keyedBehaviors.clear();
        this.recordedInvocations.clear();
        this.executionCountsByKey.clear();
    }
}
