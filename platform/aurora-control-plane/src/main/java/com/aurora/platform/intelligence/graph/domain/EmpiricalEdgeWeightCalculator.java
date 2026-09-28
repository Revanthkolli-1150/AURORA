package com.aurora.platform.intelligence.graph.domain;

import com.aurora.platform.intelligence.rca.application.EdgeWeightResult;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure closed-form mathematical calculator for empirical dependency edge weighting.
 *
 * <p>Implements Empirical Bayes shrinkage with a Beta prior:
 * <pre>
 *   w_edge(u, v) = (N_co_occur(u, v) + beta * w0) / (N_incident(u) + beta)
 *   w_final(u, v) = min(maxWeight, max(minWeight, w_edge(u, v)))
 * </pre>
 *
 * <p>Centralizes immutable architecture-policy constants and provides deterministic,
 * explainable, and defensive calculation logic.
 */
public final class EmpiricalEdgeWeightCalculator {

    // Architecture Policy Constants (Centralized and Immutable)
    public static final double DEFAULT_PRIOR_WEIGHT = 0.20;
    public static final double MIN_CLAMP_WEIGHT = 0.05;
    public static final double MAX_CLAMP_WEIGHT = 0.40;
    public static final double DEFAULT_SMOOTHING_BETA = 5.0;

    private EmpiricalEdgeWeightCalculator() {
        // Utility class
    }

    /**
     * Calculates the empirical edge weight using default policy parameters.
     */
    public static EdgeWeightResult calculate(long incidentCount, long coOccurCount) {
        return calculate(
                incidentCount,
                coOccurCount,
                DEFAULT_PRIOR_WEIGHT,
                DEFAULT_SMOOTHING_BETA,
                MIN_CLAMP_WEIGHT,
                MAX_CLAMP_WEIGHT
        );
    }

    /**
     * Calculates the empirical edge weight with explicit parameterization and defensive validation.
     */
    public static EdgeWeightResult calculate(
            long incidentCount,
            long coOccurCount,
            double priorWeight,
            double smoothingBeta,
            double minWeight,
            double maxWeight
    ) {
        // Defensive validation for invalid prior or bounds
        double safePrior = (Double.isNaN(priorWeight) || Double.isInfinite(priorWeight) || priorWeight < 0.0)
                ? DEFAULT_PRIOR_WEIGHT : priorWeight;
        double safeMin = (Double.isNaN(minWeight) || Double.isInfinite(minWeight) || minWeight < 0.0)
                ? MIN_CLAMP_WEIGHT : minWeight;
        double safeMax = (Double.isNaN(maxWeight) || Double.isInfinite(maxWeight) || maxWeight <= safeMin)
                ? MAX_CLAMP_WEIGHT : maxWeight;

        // Defensive validation for smoothing parameter
        if (Double.isNaN(smoothingBeta) || Double.isInfinite(smoothingBeta) || smoothingBeta <= 0.0) {
            double roundedPrior = roundScore(safePrior);
            return new EdgeWeightResult(
                    roundedPrior,
                    roundedPrior,
                    incidentCount,
                    coOccurCount,
                    true,
                    String.format("Empirical edge weight: %.2f (prior: %.2f, fallback: true - invalid smoothing beta).",
                            roundedPrior, roundedPrior)
            );
        }

        // Cold-start behavior: zero historical incidents
        if (incidentCount <= 0L) {
            double roundedPrior = roundScore(safePrior);
            return new EdgeWeightResult(
                    roundedPrior,
                    roundedPrior,
                    0L,
                    0L,
                    true,
                    String.format("Empirical edge weight: %.2f (prior: %.2f, smoothing beta: %.1f, co-occurrences: 0/0 incidents, fallback: true - zero historical incidents).",
                            roundedPrior, roundedPrior, smoothingBeta)
            );
        }

        // Defensive bounding on co-occurrence counts
        long safeCoOccur = Math.max(0L, Math.min(coOccurCount, incidentCount));

        // Closed-form Empirical Bayes shrinkage formula
        double rawNumerator = (double) safeCoOccur + (smoothingBeta * safePrior);
        double rawDenominator = (double) incidentCount + smoothingBeta;
        double rawWeight = rawNumerator / rawDenominator;

        if (Double.isNaN(rawWeight) || Double.isInfinite(rawWeight)) {
            double roundedPrior = roundScore(safePrior);
            return new EdgeWeightResult(
                    roundedPrior,
                    roundedPrior,
                    incidentCount,
                    safeCoOccur,
                    true,
                    String.format("Empirical edge weight: %.2f (prior: %.2f, fallback: true - non-finite calculation).",
                            roundedPrior, roundedPrior)
            );
        }

        // Apply policy clamp bounds [minWeight, maxWeight]
        double clampedWeight = Math.min(safeMax, Math.max(safeMin, rawWeight));
        double finalWeight = roundScore(clampedWeight);
        double roundedRaw = roundScore(rawWeight);

        String explanation = String.format(
                "Empirical edge weight: %.2f (raw: %.2f, prior: %.2f, smoothing beta: %.1f, co-occurrences: %d/%d incidents, fallback: false).",
                finalWeight,
                roundedRaw,
                safePrior,
                smoothingBeta,
                safeCoOccur,
                incidentCount
        );

        return new EdgeWeightResult(
                finalWeight,
                roundedRaw,
                incidentCount,
                safeCoOccur,
                false,
                explanation
        );
    }

    public static double roundScore(double score) {
        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
