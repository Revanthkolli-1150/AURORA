package com.aurora.platform.recovery.entity;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    SENT,
    DEAD_LETTER
}
