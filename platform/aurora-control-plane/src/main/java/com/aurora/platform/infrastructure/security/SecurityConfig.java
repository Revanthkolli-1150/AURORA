package com.aurora.platform.infrastructure.security;

import com.aurora.platform.common.api.ErrorResponse;
import com.aurora.platform.common.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@AutoConfiguration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final ObjectMapper objectMapper;

    public SecurityConfig(@Autowired(required = false) ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public OperatorJwtAuthenticationConverter operatorJwtAuthenticationConverter() {
        return new OperatorJwtAuthenticationConverter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   OperatorJwtAuthenticationConverter jwtConverter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Protected recovery operations require authentication
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/recovery-plan/actions/*/approve").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/incidents/*/recovery-plan/actions/*/execute").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents/*/recovery-plan/actions/*/attempts/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/incidents/*/recovery-plan/actions/*/verifications/**").authenticated()
                        // Public endpoints: telemetry ingestion, health, info, and read-only diagnostics to preserve compatibility
                        .anyRequest().permitAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(customAuthenticationEntryPoint())
                        .accessDeniedHandler(customAccessDeniedHandler())
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(customAuthenticationEntryPoint())
                        .accessDeniedHandler(customAccessDeniedHandler())
                )
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(@Value("${aurora.security.jwt.secret:default-secret-key-for-aurora-control-plane-testing-minimum-256-bits}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] validKey = new byte[32];
        System.arraycopy(keyBytes, 0, validKey, 0, Math.min(keyBytes.length, 32));
        SecretKey secretKey = new SecretKeySpec(validKey, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String traceId = MDC.get(CorrelationIdFilter.TRACE_ID_KEY);
            ErrorResponse error = ErrorResponse.of(
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "UNAUTHORIZED",
                    "Authentication is required to access this resource: " + authException.getMessage(),
                    request.getRequestURI(),
                    traceId != null ? traceId : "unknown"
            );
            objectMapper.writeValue(response.getOutputStream(), error);
        };
    }

    @Bean
    public AccessDeniedHandler customAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String traceId = MDC.get(CorrelationIdFilter.TRACE_ID_KEY);
            ErrorResponse error = ErrorResponse.of(
                    HttpServletResponse.SC_FORBIDDEN,
                    "ACCESS_DENIED",
                    "Access is denied: " + accessDeniedException.getMessage(),
                    request.getRequestURI(),
                    traceId != null ? traceId : "unknown"
            );
            objectMapper.writeValue(response.getOutputStream(), error);
        };
    }
}
