package com.aurora.platform.recovery.application.port.out;

/**
 * Vendor-neutral port for executing and querying recovery actuations.
 * <p>
 * Domain and application layers interact solely through this abstraction.
 * Concrete cloud/container actuators (Kubernetes, AWS, GCP, etc.) are strictly prohibited
 * in Phase 5A and deferred to future phases.
 */
public interface RecoveryActuatorPort {

    ActuationResult execute(ActuationRequest request);

    ActuationStatusResult queryStatus(String externalExecutionReference);
}
