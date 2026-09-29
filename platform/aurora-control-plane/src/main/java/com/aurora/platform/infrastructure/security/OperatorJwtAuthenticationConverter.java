package com.aurora.platform.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a standard OAuth2/OIDC JWT into an OperatorAuthenticationToken.
 * Extracts stable subject, username, email, and security capabilities without coupling
 * to any commercial identity provider schema.
 */
@Component
public class OperatorJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String userId = jwt.getSubject();
        if (userId == null || userId.isBlank()) {
            userId = jwt.getClaimAsString("uid");
        }
        if (userId == null || userId.isBlank()) {
            userId = "anonymous-operator";
        }

        String username = jwt.getClaimAsString("preferred_username");
        if (username == null || username.isBlank()) {
            username = jwt.getClaimAsString("name");
        }
        if (username == null || username.isBlank()) {
            username = userId;
        }

        String email = jwt.getClaimAsString("email");

        Set<RecoveryCapability> capabilities = extractCapabilities(jwt);

        AuthenticatedOperator operator = AuthenticatedOperator.builder()
                .userId(userId)
                .username(username)
                .email(email)
                .capabilities(capabilities)
                .build();

        return new OperatorAuthenticationToken(operator);
    }

    private Set<RecoveryCapability> extractCapabilities(Jwt jwt) {
        Set<RecoveryCapability> capabilities = new HashSet<>();

        // Check "capabilities" claim
        addCapabilitiesFromClaim(jwt.getClaims().get("capabilities"), capabilities);

        // Check "roles" or "authorities" claims
        addCapabilitiesFromClaim(jwt.getClaims().get("roles"), capabilities);
        addCapabilitiesFromClaim(jwt.getClaims().get("authorities"), capabilities);

        // Check standard OAuth2 "scope" or "scp"
        addCapabilitiesFromClaim(jwt.getClaims().get("scope"), capabilities);
        addCapabilitiesFromClaim(jwt.getClaims().get("scp"), capabilities);

        // Check realm_access / resource_access (Keycloak style)
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            addCapabilitiesFromClaim(map.get("roles"), capabilities);
        }

        return capabilities;
    }

    private void addCapabilitiesFromClaim(Object claimValue, Set<RecoveryCapability> target) {
        if (claimValue == null) {
            return;
        }
        if (claimValue instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) {
                    RecoveryCapability cap = RecoveryCapability.fromString(item.toString());
                    if (cap != null) {
                        target.add(cap);
                    }
                }
            }
        } else if (claimValue instanceof String str) {
            for (String part : str.split("[,\\s]+")) {
                RecoveryCapability cap = RecoveryCapability.fromString(part);
                if (cap != null) {
                    target.add(cap);
                }
            }
        }
    }
}
