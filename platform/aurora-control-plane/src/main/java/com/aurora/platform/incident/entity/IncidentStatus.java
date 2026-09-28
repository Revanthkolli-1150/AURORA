package com.aurora.platform.incident.entity;

/**
 * Deterministic lifecycle status state machine for an AURORA incident.
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>DETECTED &rarr; INVESTIGATING, FAILED</li>
 *   <li>INVESTIGATING &rarr; DIAGNOSED, FAILED</li>
 *   <li>DIAGNOSED &rarr; RECOVERING, FAILED</li>
 *   <li>RECOVERING &rarr; VERIFYING, FAILED</li>
 *   <li>VERIFYING &rarr; RESOLVED, FAILED</li>
 *   <li>RESOLVED, FAILED &rarr; Terminal (no further transitions permitted)</li>
 * </ul>
 */
public enum IncidentStatus {
    DETECTED,
    INVESTIGATING,
    DIAGNOSED,
    RECOVERING,
    VERIFYING,
    RESOLVED,
    FAILED;

    /**
     * Checks if this status is a terminal state.
     * Terminal states cannot transition to other states and cannot receive new correlated anomalies.
     */
    public boolean isTerminal() {
        return this == RESOLVED || this == FAILED;
    }

    /**
     * Determines whether transitioning from this status to the target status is legally permitted.
     *
     * @param target the desired next status
     * @return true if the transition is legal or idempotent; false otherwise
     */
    public boolean canTransitionTo(IncidentStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true; // Idempotent no-op transition
        }
        return switch (this) {
            case DETECTED -> target == INVESTIGATING || target == FAILED;
            case INVESTIGATING -> target == DIAGNOSED || target == FAILED;
            case DIAGNOSED -> target == RECOVERING || target == FAILED;
            case RECOVERING -> target == VERIFYING || target == FAILED;
            case VERIFYING -> target == RESOLVED || target == FAILED;
            case RESOLVED, FAILED -> false; // Terminal states cannot transition
        };
    }

    /**
     * Validates that transitioning to the target status is permitted.
     * Throws {@link IllegalStateException} if the transition violates the lifecycle policy.
     *
     * @param target the desired next status
     * @throws IllegalStateException if the transition is illegal
     */
    public void validateTransitionTo(IncidentStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(String.format(
                    "Illegal incident lifecycle transition from %s to %s", this, target));
        }
    }
}
