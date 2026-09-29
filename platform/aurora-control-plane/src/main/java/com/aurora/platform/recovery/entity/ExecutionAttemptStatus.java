package com.aurora.platform.recovery.entity;

public enum ExecutionAttemptStatus {
    REQUESTED,
    DISPATCHED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT
}
