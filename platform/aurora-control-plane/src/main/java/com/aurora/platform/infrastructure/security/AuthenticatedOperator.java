package com.aurora.platform.infrastructure.security;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable representation of an authenticated operator in the control plane context.
 */
@Getter
@Builder
public class AuthenticatedOperator {

    private final String userId;
    private final String username;
    private final String email;
    private final Set<RecoveryCapability> capabilities;

    public AuthenticatedOperator(String userId, String username, String email, Set<RecoveryCapability> capabilities) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.username = username != null && !username.isBlank() ? username : userId;
        this.email = email;
        this.capabilities = capabilities != null ? Collections.unmodifiableSet(capabilities) : Collections.emptySet();
    }

    public boolean hasCapability(RecoveryCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean canApprove() {
        return hasCapability(RecoveryCapability.RECOVERY_APPROVE)
                || hasCapability(RecoveryCapability.RECOVERY_APPROVE_PRODUCTION)
                || isAdmin();
    }

    public boolean canApprove(String environment) {
        if (isAdmin()) {
            return true;
        }
        if ("production".equalsIgnoreCase(environment)) {
            return hasCapability(RecoveryCapability.RECOVERY_APPROVE_PRODUCTION);
        }
        return canApprove();
    }

    public boolean isAdmin() {
        return hasCapability(RecoveryCapability.RECOVERY_ADMIN);
    }
}
