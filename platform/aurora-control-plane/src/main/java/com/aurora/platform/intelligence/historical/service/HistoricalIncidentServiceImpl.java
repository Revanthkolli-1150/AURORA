package com.aurora.platform.intelligence.historical.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.intelligence.historical.application.port.out.HistoricalIncidentQueryPort;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.historical.domain.model.SimilarityScore;
import com.aurora.platform.intelligence.historical.domain.service.IncidentSimilarityCalculator;
import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.intelligence.historical.dto.SimilarityBreakdownResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Authoritative application service implementing {@link HistoricalIncidentService}.
 * Orchestrates deterministic similarity evaluation over the candidate corpus of resolved incidents.
 */
@Service
@Transactional(readOnly = true)
public class HistoricalIncidentServiceImpl implements HistoricalIncidentService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalIncidentServiceImpl.class);

    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 20;
    public static final double DEFAULT_MIN_SCORE = 0.30;
    public static final int CORPUS_MAX_CANDIDATES = 200;

    /**
     * Deterministic three-tier ranking comparator:
     * 1. similarityScore DESC (higher match comes first)
     * 2. resolvedAt DESC (more recently resolved comes first)
     * 3. historicalIncidentId ASC (lexicographical tie-breaking)
     */
    private static final Comparator<SimilarIncidentResponse> MATCH_COMPARATOR = Comparator
            .comparingDouble(SimilarIncidentResponse::similarityScore).reversed()
            .thenComparing(SimilarIncidentResponse::resolvedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(SimilarIncidentResponse::historicalIncidentId);

    private final HistoricalIncidentQueryPort queryPort;
    private final IncidentSimilarityCalculator similarityCalculator;

    public HistoricalIncidentServiceImpl(HistoricalIncidentQueryPort queryPort) {
        this.queryPort = queryPort;
        this.similarityCalculator = new IncidentSimilarityCalculator();
    }

    @Override
    public List<SimilarIncidentResponse> findSimilarIncidents(UUID incidentId, Integer limit, Double minScore) {
        Objects.requireNonNull(incidentId, "incidentId must not be null");

        int effectiveLimit = (limit != null) ? limit : DEFAULT_LIMIT;
        if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + MAX_LIMIT + " (requested: " + effectiveLimit + ")"
            );
        }

        double effectiveMinScore = (minScore != null) ? minScore : DEFAULT_MIN_SCORE;
        if (Double.isNaN(effectiveMinScore) || effectiveMinScore < 0.0 || effectiveMinScore > 1.0) {
            throw new IllegalArgumentException(
                    "minScore must be between 0.00 and 1.00 (requested: " + effectiveMinScore + ")"
            );
        }

        // 1. Resolve target incident signature
        IncidentSignature targetSignature = queryPort.findSignature(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident with ID '" + incidentId + "' not found"));

        log.debug("Evaluating historical similarity for incident {} (resourceType={}, severity={}, anomalousMetrics={}, topologyTokens={})",
                incidentId, targetSignature.resourceType(), targetSignature.severity(),
                targetSignature.anomalousMetrics(), targetSignature.topologyTokens());

        // 2. Fetch candidate corpus: most recent 200 RESOLVED incidents (excluding target)
        List<IncidentSignature> corpus = queryPort.findCandidateCorpusSignatures(incidentId, CORPUS_MAX_CANDIDATES);

        // 3. Compute deterministic similarity for all candidates
        List<SimilarIncidentResponse> qualifiedMatches = new ArrayList<>();
        for (IncidentSignature candidate : corpus) {
            SimilarityScore score = similarityCalculator.calculate(targetSignature, candidate);

            if (score.compositeScore() >= effectiveMinScore) {
                Long durationSeconds = null;
                if (candidate.detectedAt() != null && candidate.resolvedAt() != null) {
                    durationSeconds = Duration.between(candidate.detectedAt(), candidate.resolvedAt()).getSeconds();
                }

                SimilarityBreakdownResponse breakdown = new SimilarityBreakdownResponse(
                        score.metricScore(),
                        score.topologyScore(),
                        score.resourceScore(),
                        score.severityScore(),
                        score.matchedMetrics(),
                        score.matchedTopologyTokens()
                );

                SimilarIncidentResponse match = new SimilarIncidentResponse(
                        candidate.incidentId(),
                        candidate.resourceId(),
                        candidate.resourceName(),
                        candidate.resourceType(),
                        candidate.severity(),
                        score.compositeScore(),
                        breakdown,
                        candidate.primaryRcaCause(),
                        candidate.resolvedAt(),
                        durationSeconds,
                        score.explanation()
                );

                qualifiedMatches.add(match);
            }
        }

        // 4. Sort deterministically and apply limit
        List<SimilarIncidentResponse> rankedResults = qualifiedMatches.stream()
                .sorted(MATCH_COMPARATOR)
                .limit(effectiveLimit)
                .collect(Collectors.toList());

        log.info("Found {} similar historical incidents for incident {} (evaluated {} candidates, limit={})",
                rankedResults.size(), incidentId, corpus.size(), effectiveLimit);

        return rankedResults;
    }
}
