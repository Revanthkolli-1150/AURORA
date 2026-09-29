package com.aurora.platform.intelligence.graph.application.port.out;

import com.aurora.platform.incident.entity.IncidentEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Output port for querying historical incidents and anomalous co-occurrences.
 */
public interface HistoricalCoOccurrenceQueryPort {

    /**
     * Retrieves historical resolved incidents for resource u strictly prior to beforeDetectedAt,
     * excluding the target incident, bounded by limit and ordered deterministically (detectedAt DESC, id ASC).
     */
    List<IncidentEntity> findHistoricalResolvedIncidents(
            UUID resourceId,
            Instant beforeDetectedAt,
            UUID excludedIncidentId,
            int limit
    );

    /**
     * Counts how many of the given historical incidents had an anomalous co-occurrence
     * with the upstream dependency candidate v within each incident's lookback window.
     */
    long countAnomalousCoOccurrences(
            UUID candidateResourceId,
            List<IncidentEntity> historicalIncidents,
            long lookbackMinutes
    );
}
