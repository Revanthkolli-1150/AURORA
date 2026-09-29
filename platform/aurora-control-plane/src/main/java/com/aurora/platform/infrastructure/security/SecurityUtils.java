package com.aurora.platform.infrastructure.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Utility for resolving the current AuthenticatedOperator from SecurityContextHolder.
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<AuthenticatedOperator> getCurrentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        if (auth.getPrincipal() instanceof AuthenticatedOperator operator) {
            return Optional.of(operator);
        }
        if (auth instanceof OperatorAuthenticationToken token) {
            return Optional.of(token.getPrincipal());
        }
        return Optional.empty();
    }

    public static AuthenticatedOperator getRequiredOperator() {
        return getCurrentOperator().orElseThrow(() ->
                new AccessDeniedException("Unauthenticated access denied: an authenticated operator principal is required for this operation"));
    }
}
