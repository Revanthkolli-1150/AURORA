package com.aurora.platform.intelligence.narrative.domain;

import com.aurora.platform.intelligence.narrative.application.dto.HistoricalIncidentSummary;
import com.aurora.platform.intelligence.narrative.application.dto.IncidentNarrativeContext;
import com.aurora.platform.intelligence.narrative.application.dto.RcaCandidateSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Enterprise data sanitization utility enforcing the security classification policy in ADR-007 Section 11.
 *
 * <p>Supported Redaction & Scrubbing Capabilities:
 * <ul>
 *   <li><b>Bearer & Auth Tokens:</b> Bearer tokens (JWT format or alphanumeric tokens) replaced with {@code Bearer [REDACTED_TOKEN]}.</li>
 *   <li><b>Passwords & Secrets:</b> Key-value secret assignments (e.g. password=..., api_key=..., secret=...) replaced with {@code key=[REDACTED_SECRET]}.</li>
 *   <li><b>AWS / Cloud API Keys:</b> Standard pattern matches (e.g. AKIA...) replaced with {@code [REDACTED_API_KEY]}.</li>
 *   <li><b>Private IPv4 Addresses:</b> RFC 1918 subnets (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, loopback 127.0.0.0/8) replaced with {@code [REDACTED_IP]}.</li>
 *   <li><b>Private IPv6 Addresses:</b> Unique local addresses (fc00::/7) and loopback (::1) replaced with {@code [REDACTED_IPV6]}.</li>
 *   <li><b>Internal Hostnames & FQDNs:</b> Internal infrastructure domain suffixes (.internal, .local, .corp, .compute.internal) replaced with {@code [REDACTED_HOST]}.</li>
 *   <li><b>Customer PII (Email addresses):</b> Replaced with {@code [REDACTED_EMAIL]}.</li>
 * </ul>
 *
 * <p>Allowed without Redaction:
 * <ul>
 *   <li>Logical resource names (e.g. {@code order-service}, {@code postgres-db-primary})</li>
 *   <li>Metric names (e.g. {@code cpu_usage}, {@code db_connection_pool_active})</li>
 *   <li>Deterministic numerical scores, timestamps, and severity classifications.</li>
 * </ul>
 *
 * <i>Note: Regex-based scrubbing provides targeted defense-in-depth but does not guarantee discovery
 * of high-entropy secrets in arbitrary unstructured natural language.</i>
 */
public final class DataSanitizer {

    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+[a-zA-Z0-9_\\-\\.]{15,}\\b"
    );

    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}\\b"
    );

    private static final Pattern KEY_VALUE_SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?token|auth[_-]?token|private[_-]?key)\\s*[:=]\\s*['\"]?[^\\s,'\";&]+['\"]?"
    );

    private static final Pattern AWS_KEY_PATTERN = Pattern.compile(
            "\\b(AKIA|ABIA|ACCA|ASIA)[0-9A-Z]{16}\\b"
    );

    private static final Pattern PRIVATE_IPV4_PATTERN = Pattern.compile(
            "\\b(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b"
    );

    private static final Pattern PRIVATE_IPV6_PATTERN = Pattern.compile(
            "(?i)(?:\\b(?:fc00|fd[0-9a-f]{2})(:[0-9a-f:]*)?[0-9a-f]+\\b|(?<=[^0-9a-fA-F:]|^)::1(?=[^0-9a-fA-F:]|$))"
    );

    private static final Pattern INTERNAL_HOSTNAME_PATTERN = Pattern.compile(
            "(?i)\\b[a-zA-Z0-9_-]+(?:\\.[a-zA-Z0-9_-]+)*(?:\\.internal|\\.local|\\.corp|\\.lan|\\.compute\\.internal|\\.us-east|\\.us-west|\\.eu-central)(?:[a-zA-Z0-9_.-]*)\\b"
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b"
    );

    private DataSanitizer() {
        // Utility class
    }

    /**
     * Sanitizes unstructured text content by applying all redaction filters.
     *
     * @param input Raw text string (may be null)
     * @return Sanitized text with sensitive data masked
     */
    public static String sanitizeText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        String result = input;
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("Bearer [REDACTED_TOKEN]");
        result = JWT_PATTERN.matcher(result).replaceAll("[REDACTED_JWT]");
        result = KEY_VALUE_SECRET_PATTERN.matcher(result).replaceAll("$1=[REDACTED_SECRET]");
        result = AWS_KEY_PATTERN.matcher(result).replaceAll("[REDACTED_API_KEY]");
        result = PRIVATE_IPV4_PATTERN.matcher(result).replaceAll("[REDACTED_IP]");
        result = PRIVATE_IPV6_PATTERN.matcher(result).replaceAll("[REDACTED_IPV6]");
        result = INTERNAL_HOSTNAME_PATTERN.matcher(result).replaceAll("[REDACTED_HOST]");
        result = EMAIL_PATTERN.matcher(result).replaceAll("[REDACTED_EMAIL]");

        return result;
    }

    /**
     * Sanitizes an entire IncidentNarrativeContext aggregate before prompt serialization.
     *
     * @param context Raw input context assembled from Phase 1 & Phase 2 services
     * @return Sanitized context with internal hostnames, descriptions, and explanations scrubbed
     */
    public static IncidentNarrativeContext sanitize(IncidentNarrativeContext context) {
        if (context == null) {
            return null;
        }

        String sanitizedTitle = sanitizeText(context.incidentTitle());
        String sanitizedDesc = sanitizeText(context.incidentDescription());
        String sanitizedSummary = sanitizeText(context.deterministicRcaSummary());

        RcaCandidateSummary sanitizedPrimary = sanitizeCandidate(context.primaryCandidate());

        List<RcaCandidateSummary> sanitizedSecondary = null;
        if (context.topSecondaryCandidates() != null) {
            sanitizedSecondary = context.topSecondaryCandidates().stream()
                    .map(DataSanitizer::sanitizeCandidate)
                    .toList();
        }

        List<HistoricalIncidentSummary> sanitizedHistorical = null;
        if (context.similarHistoricalIncidents() != null) {
            sanitizedHistorical = context.similarHistoricalIncidents().stream()
                    .map(h -> new HistoricalIncidentSummary(
                            h.historicalIncidentId(),
                            h.resourceName(),
                            h.similarityScore(),
                            sanitizeText(h.primaryRcaCause()),
                            h.resolutionDurationSeconds()
                    ))
                    .toList();
        }

        List<String> sanitizedUpstream = null;
        if (context.upstreamDependencies() != null) {
            sanitizedUpstream = context.upstreamDependencies().stream()
                    .map(DataSanitizer::sanitizeText)
                    .toList();
        }

        List<String> sanitizedDownstream = null;
        if (context.downstreamDependents() != null) {
            sanitizedDownstream = context.downstreamDependents().stream()
                    .map(DataSanitizer::sanitizeText)
                    .toList();
        }

        return new IncidentNarrativeContext(
                context.incidentId(),
                sanitizedTitle,
                sanitizedDesc,
                context.severity(),
                context.status(),
                context.detectedAt(),
                context.investigatedResourceId(),
                sanitizeText(context.investigatedResourceName()),
                context.investigatedResourceType(),
                context.environment(),
                context.rcaAnalysisId(),
                context.rcaConfidence(),
                context.rcaConfidenceLevel(),
                sanitizedSummary,
                sanitizedPrimary,
                sanitizedSecondary,
                sanitizedHistorical,
                sanitizedUpstream,
                sanitizedDownstream,
                context.generatedAt()
        );
    }

    private static RcaCandidateSummary sanitizeCandidate(RcaCandidateSummary candidate) {
        if (candidate == null) {
            return null;
        }

        List<String> sanitizedEvidenceItems = null;
        if (candidate.evidenceItemSummaries() != null) {
            sanitizedEvidenceItems = candidate.evidenceItemSummaries().stream()
                    .map(DataSanitizer::sanitizeText)
                    .toList();
        }

        return new RcaCandidateSummary(
                candidate.candidateResourceId(),
                sanitizeText(candidate.candidateResourceName()),
                candidate.candidateMetric(),
                sanitizeText(candidate.candidateCause()),
                candidate.evidenceScore(),
                candidate.rank(),
                sanitizeText(candidate.explanation()),
                candidate.primaryCandidate(),
                sanitizedEvidenceItems
        );
    }
}
