package com.aurora.platform.intelligence.graph.service;

import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.intelligence.graph.application.port.out.HistoricalCoOccurrenceQueryPort;
import com.aurora.platform.intelligence.graph.config.GraphIntelligenceProperties;
import com.aurora.platform.intelligence.graph.domain.EmpiricalEdgeWeightCalculator;
import com.aurora.platform.intelligence.rca.application.EdgeWeightProvider;
import com.aurora.platform.intelligence.rca.application.EdgeWeightResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service providing empirical dependency edge weights for RCA candidate evaluation.
 *
 * <p>Implements {@link EdgeWeightProvider} port, ensuring complete synchronous resilience,
 * zero async overhead, and graceful degradation to static Phase 2B prior weight (0.20).
 */
@Service
public class EmpiricalEdgeWeightProvider implements EdgeWeightProvider {

    private static final Logger log = LoggerFactory.getLogger(EmpiricalEdgeWeightProvider.class);

    private final HistoricalCoOccurrenceQueryPort queryPort;
    private final GraphIntelligenceProperties properties;

    public EmpiricalEdgeWeightProvider(
            HistoricalCoOccurrenceQueryPort queryPort,
            GraphIntelligenceProperties properties) {
        this.queryPort = queryPort;
        this.properties = properties;
    }

    @Override
    public EdgeWeightResult getEdgeWeight(
            UUID investigatedResourceId,
            UUID candidateResourceId,
            Instant targetIncidentDetectedAt,
            UUID targetIncidentId) {

        if (!properties.isEnabled()) {
            return EdgeWeightResult.fallback(
                    EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                    "Empirical graph weighting disabled by configuration; using static prior (0.20)."
            );
        }

        if (investigatedResourceId == null || candidateResourceId == null || targetIncidentDetectedAt == null) {
            return EdgeWeightResult.fallback(
                    EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                    "Invalid arguments for edge weight calculation; using static prior (0.20)."
            );
        }

        try {
            // 1. Retrieve bounded historical resolved incidents on investigated resource u prior to detection
            List<IncidentEntity> historicalIncidents = queryPort.findHistoricalResolvedIncidents(
                    investigatedResourceId,
                    targetIncidentDetectedAt,
                    targetIncidentId,
                    properties.getMaxHistoryIncidents()
            );

            long incidentCount = historicalIncidents.size();
            if (incidentCount == 0L) {
                return EmpiricalEdgeWeightCalculator.calculate(
                        0L,
                        0L,
                        EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                        properties.getSmoothingBeta(),
                        EmpiricalEdgeWeightCalculator.MIN_CLAMP_WEIGHT,
                        EmpiricalEdgeWeightCalculator.MAX_CLAMP_WEIGHT
                );
            }

            // 2. Count qualifying anomalous co-occurrences of candidate resource v during lookback windows
            long coOccurCount = queryPort.countAnomalousCoOccurrences(
                    candidateResourceId,
                    historicalIncidents,
                    properties.getLookbackMinutes()
            );

            // 3. Compute Empirical Bayes smoothed and clamped edge weight
            return EmpiricalEdgeWeightCalculator.calculate(
                    incidentCount,
                    coOccurCount,
                    EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                    properties.getSmoothingBeta(),
                    EmpiricalEdgeWeightCalculator.MIN_CLAMP_WEIGHT,
                    EmpiricalEdgeWeightCalculator.MAX_CLAMP_WEIGHT
            );

        } catch (Exception ex) {
            log.warn("Synchronous fallback to prior {} for edge {} -> {} due to query exception: {}",
                    EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                    investigatedResourceId,
                    candidateResourceId,
                    ex.getMessage());

            return EdgeWeightResult.fallback(
                    EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                    String.format("Empirical edge weight: %.2f (prior: %.2f, fallback: true - query error).",
                            EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT,
                            EmpiricalEdgeWeightCalculator.DEFAULT_PRIOR_WEIGHT)
            );
        }
    }
}
