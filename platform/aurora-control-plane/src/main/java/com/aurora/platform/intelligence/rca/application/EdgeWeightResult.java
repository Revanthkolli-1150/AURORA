package com.aurora.platform.intelligence.rca.application;

/**
 * Result of an empirical dependency edge weight calculation for RCA evidence.
 *
 * @param finalWeight   the final clamped weight in [0.05, 0.40]
 * @param rawWeight     the unclamped raw weight from Empirical Bayes formula
 * @param incidentCount N_incident, total historical resolved incidents for the investigated resource
 * @param coOccurCount  N_co_occur, historical incidents where the upstream candidate had an anomaly
 * @param fallbackUsed  whether fallback prior weight was used (e.g. cold start or error)
 * @param explanation   human-readable explanation detailing calculation and parameters
 */
public record EdgeWeightResult(
        double finalWeight,
        double rawWeight,
        long incidentCount,
        long coOccurCount,
        boolean fallbackUsed,
        String explanation
) {
    public static EdgeWeightResult fallback(double weight, String reason) {
        return new EdgeWeightResult(weight, weight, 0L, 0L, true, reason);
    }
}
