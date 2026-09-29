package com.aurora.platform.infrastructure.security;

/**
 * Concrete security capabilities governing controlled reliability and recovery operations.
 */
public enum RecoveryCapability {
    RECOVERY_VIEW,
    RECOVERY_APPROVE,
    RECOVERY_APPROVE_PRODUCTION,
    RECOVERY_ADMIN;

    public static RecoveryCapability fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.startsWith("CAPABILITY_")) {
            normalized = normalized.substring("CAPABILITY_".length());
        } else if (normalized.startsWith("ROLE_")) {
            normalized = normalized.substring("ROLE_".length());
        } else if (normalized.startsWith("SCOPE_")) {
            normalized = normalized.substring("SCOPE_".length());
        }
        for (RecoveryCapability cap : values()) {
            if (cap.name().equalsIgnoreCase(normalized) || cap.name().equalsIgnoreCase("RECOVERY_" + normalized)) {
                return cap;
            }
        }
        return null;
    }
}
