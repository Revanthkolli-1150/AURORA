package com.aurora.platform.intelligence.narrative.dto;

import java.util.List;
import java.util.UUID;

/**
 * Public response DTO encapsulating the generated or cached narrative summary for an incident.
 */
public record IncidentNarrativeResponse(
        UUID id,
        UUID incidentId,
        UUID rcaAnalysisId,
        String headline,
        String executiveSummary,
        List<String> observedSymptoms,
        String rootCauseExplanation,
        List<String> secondaryHypothesesEvaluated,
        String historicalContextNarrative,
        List<String> suggestedInvestigationSteps,
        List<String> caveatsAndUncertainties,
        NarrativeMetadataResponse metadata
) {
}
