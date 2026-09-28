package com.aurora.platform.common.exception;

/**
 * Exception thrown when an operation conflicts with current state, returning HTTP 409 Conflict.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
