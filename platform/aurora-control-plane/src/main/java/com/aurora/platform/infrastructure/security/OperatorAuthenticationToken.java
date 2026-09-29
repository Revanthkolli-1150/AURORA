package com.aurora.platform.infrastructure.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * Spring Security authentication token holding an AuthenticatedOperator principal.
 */
public class OperatorAuthenticationToken extends AbstractAuthenticationToken {

    private final AuthenticatedOperator principal;

    public OperatorAuthenticationToken(AuthenticatedOperator principal) {
        super(toAuthorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static Collection<? extends GrantedAuthority> toAuthorities(AuthenticatedOperator operator) {
        if (operator == null || operator.getCapabilities() == null) {
            return Collections.emptyList();
        }
        return operator.getCapabilities().stream()
                .map(cap -> new SimpleGrantedAuthority("CAPABILITY_" + cap.name()))
                .collect(Collectors.toSet());
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public AuthenticatedOperator getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal != null ? principal.getUsername() : "";
    }
}
