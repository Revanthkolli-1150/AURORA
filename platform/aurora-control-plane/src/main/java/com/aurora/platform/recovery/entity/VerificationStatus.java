package com.aurora.platform.recovery.entity;

public enum VerificationStatus {
    SCHEDULED,
    OBSERVING,
    VERIFIED_HEALTHY,
    VERIFIED_DEGRADED,
    VERIFIED_INCONCLUSIVE,
    TIMED_OUT
}
