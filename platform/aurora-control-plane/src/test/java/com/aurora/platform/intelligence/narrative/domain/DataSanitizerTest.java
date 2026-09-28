package com.aurora.platform.intelligence.narrative.domain;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.intelligence.narrative.application.dto.HistoricalIncidentSummary;
import com.aurora.platform.intelligence.narrative.application.dto.IncidentNarrativeContext;
import com.aurora.platform.intelligence.narrative.application.dto.RcaCandidateSummary;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataSanitizerTest {

    @Test
    @DisplayName("Sanitize handles null and blank text gracefully")
    void testNullAndBlankText() {
        assertThat(DataSanitizer.sanitizeText(null)).isNull();
        assertThat(DataSanitizer.sanitizeText("")).isEqualTo("");
        assertThat(DataSanitizer.sanitizeText("   ")).isEqualTo("   ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password=mySecretPass123",
            "password: 'superSecret'",
            "passwd=admin123",
            "pwd = secret_value",
            "secret=\"topSecretToken\"",
            "api_key=AIzaSyD-1234567890",
            "apiKey=abcdef123456",
            "access_token=ghp_1234567890abcdef",
            "auth_token=eyJhbGciOi...",
            "private_key=MIIEvgIBADANBgkqhkiG9w0BAQEFAASC..."
    })
    @DisplayName("Adversarial: Key-value secrets are scrubbed to [REDACTED_SECRET]")
    void testKeyValueSecretScrubbing(String secretInput) {
        String sanitized = DataSanitizer.sanitizeText(secretInput);
        assertThat(sanitized).contains("[REDACTED_SECRET]");
        assertThat(sanitized).doesNotContain("mySecretPass123");
        assertThat(sanitized).doesNotContain("admin123");
        assertThat(sanitized).doesNotContain("AIzaSyD");
    }

    @Test
    @DisplayName("Adversarial: Bearer tokens and JWTs are scrubbed")
    void testBearerAndJwtTokens() {
        String input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.signature";
        String sanitized = DataSanitizer.sanitizeText(input);

        assertThat(sanitized).doesNotContain("eyJhbGciOiJIUzI1Ni");
        assertThat(sanitized).contains("[REDACTED_TOKEN]");
    }

    @Test
    @DisplayName("Adversarial: AWS-style access keys are scrubbed")
    void testAwsAccessKey() {
        String input = "Deployment failed using key AKIAIOSFODNN7EXAMPLE in us-east-1";
        String sanitized = DataSanitizer.sanitizeText(input);

        assertThat(sanitized).contains("[REDACTED_API_KEY]");
        assertThat(sanitized).doesNotContain("AKIAIOSFODNN7EXAMPLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Connection failed to 10.0.1.45:5432",
            "Timeout connecting to 172.20.14.8 on port 8080",
            "Gateway unreachable at 192.168.1.100",
            "Probe failed on loopback 127.0.0.1:9090"
    })
    @DisplayName("Adversarial: Private IPv4 addresses are masked to [REDACTED_IP]")
    void testPrivateIpv4Masking(String ipInput) {
        String sanitized = DataSanitizer.sanitizeText(ipInput);
        assertThat(sanitized).contains("[REDACTED_IP]");
        assertThat(sanitized).doesNotContain("10.0.1.45");
        assertThat(sanitized).doesNotContain("172.20.14.8");
        assertThat(sanitized).doesNotContain("192.168.1.100");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Peer fc00::1:2345 unreachable",
            "Local probe on ::1 failed with ECONNREFUSED"
    })
    @DisplayName("Adversarial: Private IPv6 addresses are masked to [REDACTED_IPV6]")
    void testPrivateIpv6Masking(String ipv6Input) {
        String sanitized = DataSanitizer.sanitizeText(ipv6Input);
        assertThat(sanitized).contains("[REDACTED_IPV6]");
        assertThat(sanitized).doesNotContain("fc00::");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Database host postgres-primary.corp.internal is down",
            "Node ip-10-0-12-3.us-east.compute.internal terminated",
            "Redis cluster redis-node-1.cache.local timed out"
    })
    @DisplayName("Adversarial: Internal infrastructure hostnames are masked to [REDACTED_HOST]")
    void testInternalHostnamesMasking(String hostInput) {
        String sanitized = DataSanitizer.sanitizeText(hostInput);
        assertThat(sanitized).contains("[REDACTED_HOST]");
        assertThat(sanitized).doesNotContain("postgres-primary.corp.internal");
        assertThat(sanitized).doesNotContain("compute.internal");
    }

    @Test
    @DisplayName("Adversarial: Customer email addresses are masked to [REDACTED_EMAIL]")
    void testCustomerEmailScrubbing() {
        String input = "Incident triggered by alert notification to sre-oncall@enterprise.com";
        String sanitized = DataSanitizer.sanitizeText(input);

        assertThat(sanitized).contains("[REDACTED_EMAIL]");
        assertThat(sanitized).doesNotContain("sre-oncall@enterprise.com");
    }

    @Test
    @DisplayName("Mixed content: Allowed domain identifiers and metrics are preserved while secrets are masked")
    void testMixedAllowedAndSensitiveContent() {
        String input = "Service order-service encountered CPU spike (cpu_usage: 98.4%) on host srv-order-01.us-east.internal with db password=secretpass";
        String sanitized = DataSanitizer.sanitizeText(input);

        // Allowed content preserved
        assertThat(sanitized).contains("order-service");
        assertThat(sanitized).contains("cpu_usage: 98.4%");

        // Sensitive content masked
        assertThat(sanitized).contains("[REDACTED_HOST]");
        assertThat(sanitized).contains("password=[REDACTED_SECRET]");
        assertThat(sanitized).doesNotContain("secretpass");
        assertThat(sanitized).doesNotContain("srv-order-01.us-east.internal");
    }

    @Test
    @DisplayName("Idempotency: Already-sanitized text remains stable when passed through sanitizer again")
    void testAlreadySanitizedContentStability() {
        String alreadySanitized = "Resource order-service on [REDACTED_HOST] with password=[REDACTED_SECRET]";
        String sanitizedAgain = DataSanitizer.sanitizeText(alreadySanitized);

        assertThat(sanitizedAgain).isEqualTo(alreadySanitized);
    }

    @Test
    @DisplayName("Context Sanitization: Fully sanitizes aggregate IncidentNarrativeContext")
    void testAggregateContextSanitization() {
        UUID incidentId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID rcaId = UUID.randomUUID();

        RcaCandidateSummary candidate = new RcaCandidateSummary(
                UUID.randomUUID(),
                "postgres-db-1",
                "db_connection_pool",
                "Connection pool exhausted on 10.0.4.15 with password=dbpass123",
                0.85,
                1,
                "Anomaly on host db-01.corp.internal",
                true,
                List.of("Observed error on 192.168.0.5", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig")
        );

        HistoricalIncidentSummary historical = new HistoricalIncidentSummary(
                UUID.randomUUID(),
                "order-service",
                0.75,
                "Connection timeout to 10.0.0.1",
                300L
        );

        IncidentNarrativeContext context = new IncidentNarrativeContext(
                incidentId,
                "Checkout latency breach on srv-api-01.us-east.internal",
                "Trace showed failure with api_key=secretval and AWS key AKIAIOSFODNN7EXAMPLE",
                IncidentSeverity.HIGH,
                IncidentStatus.INVESTIGATING,
                Instant.now(),
                resourceId,
                "order-service",
                ResourceType.SERVICE,
                "production",
                rcaId,
                0.85,
                "VERY_HIGH",
                "RCA completed on 10.0.1.20 with secret=dbpass",
                candidate,
                List.of(),
                List.of(historical),
                List.of("payment-service"),
                List.of("frontend-app"),
                Instant.now()
        );

        IncidentNarrativeContext sanitized = DataSanitizer.sanitize(context);

        assertThat(sanitized.incidentTitle()).contains("[REDACTED_HOST]");
        assertThat(sanitized.incidentDescription()).contains("[REDACTED_API_KEY]");
        assertThat(sanitized.incidentDescription()).contains("[REDACTED_SECRET]");
        assertThat(sanitized.deterministicRcaSummary()).contains("[REDACTED_IP]");
        assertThat(sanitized.deterministicRcaSummary()).contains("secret=[REDACTED_SECRET]");

        assertThat(sanitized.primaryCandidate().candidateCause()).contains("[REDACTED_IP]");
        assertThat(sanitized.primaryCandidate().candidateCause()).contains("password=[REDACTED_SECRET]");
        assertThat(sanitized.primaryCandidate().explanation()).contains("[REDACTED_HOST]");
        assertThat(sanitized.primaryCandidate().evidenceItemSummaries().get(0)).contains("[REDACTED_IP]");
        assertThat(sanitized.primaryCandidate().evidenceItemSummaries().get(1)).contains("[REDACTED_TOKEN]");

        assertThat(sanitized.similarHistoricalIncidents().get(0).primaryRcaCause()).contains("[REDACTED_IP]");

        // Allowed logical context preserved
        assertThat(sanitized.investigatedResourceName()).isEqualTo("order-service");
        assertThat(sanitized.upstreamDependencies()).containsExactly("payment-service");
        assertThat(sanitized.downstreamDependents()).containsExactly("frontend-app");
    }
}
