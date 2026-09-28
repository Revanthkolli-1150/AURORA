package com.aurora.platform.intelligence.graph.infrastructure.adapter;

import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.graph.application.port.out.HistoricalCoOccurrenceQueryPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Infrastructure query adapter for retrieving historical incident populations and anomalous co-occurrences.
 *
 * <p>Uses bounded batch queries to prevent unbounded scans and N+1 query explosion.
 */
@Component
@Transactional(readOnly = true)
public class HistoricalCoOccurrenceQueryAdapter implements HistoricalCoOccurrenceQueryPort {

    private final IncidentRepository incidentRepository;
    private final IncidentAnomalyEvidenceRepository evidenceRepository;

    public HistoricalCoOccurrenceQueryAdapter(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository evidenceRepository) {
        this.incidentRepository = incidentRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Override
    public List<IncidentEntity> findHistoricalResolvedIncidents(
            UUID resourceId,
            Instant beforeDetectedAt,
            UUID excludedIncidentId,
            int limit) {
        if (resourceId == null || beforeDetectedAt == null) {
            return Collections.emptyList();
        }

        int boundedLimit = Math.max(1, Math.min(100, limit));
        return incidentRepository.findHistoricalResolvedIncidents(
                resourceId,
                IncidentStatus.RESOLVED,
                beforeDetectedAt,
                excludedIncidentId,
                PageRequest.of(0, boundedLimit)
        );
    }

    @Override
    public long countAnomalousCoOccurrences(
            UUID candidateResourceId,
            List<IncidentEntity> historicalIncidents,
            long lookbackMinutes) {
        if (candidateResourceId == null || historicalIncidents == null || historicalIncidents.isEmpty()) {
            return 0L;
        }

        long safeLookback = Math.max(1L, lookbackMinutes);

        // 1. Determine bounding time range across all historical incidents
        Instant minTime = null;
        Instant maxTime = null;
        List<UUID> incidentIds = new ArrayList<>(historicalIncidents.size());

        for (IncidentEntity inc : historicalIncidents) {
            if (inc.getDetectedAt() != null) {
                Instant start = inc.getDetectedAt().minus(Duration.ofMinutes(safeLookback));
                Instant end = inc.getDetectedAt();

                if (minTime == null || start.isBefore(minTime)) {
                    minTime = start;
                }
                if (maxTime == null || end.isAfter(maxTime)) {
                    maxTime = end;
                }
            }
            incidentIds.add(inc.getId());
        }

        if (minTime == null || maxTime == null) {
            return 0L;
        }

        // 2. Batch fetch candidate anomaly evidence within bounding window
        List<IncidentAnomalyEvidenceEntity> timeBoundedEvidence = evidenceRepository
                .findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(candidateResourceId, minTime, maxTime);

        // 3. Batch fetch candidate anomaly evidence attached to these specific incident IDs
        List<IncidentAnomalyEvidenceEntity> incidentEvidence = evidenceRepository
                .findByIncidentIdIn(incidentIds);

        List<IncidentAnomalyEvidenceEntity> allEvidence = new ArrayList<>(timeBoundedEvidence);
        for (IncidentAnomalyEvidenceEntity ev : incidentEvidence) {
            if (candidateResourceId.equals(ev.getResourceId())) {
                allEvidence.add(ev);
            }
        }

        // 4. For each historical incident, determine if candidate experienced an anomalous co-occurrence
        long coOccurCount = 0L;

        for (IncidentEntity inc : historicalIncidents) {
            if (inc.getDetectedAt() == null) {
                continue;
            }

            Instant windowStart = inc.getDetectedAt().minus(Duration.ofMinutes(safeLookback));
            Instant windowEnd = inc.getDetectedAt();
            UUID incidentId = inc.getId();

            boolean hasCoOccurrence = allEvidence.stream().anyMatch(ev -> {
                boolean matchesIncident = incidentId.equals(ev.getIncidentId());
                boolean inLookbackWindow = ev.getObservedAt() != null
                        && !ev.getObservedAt().isBefore(windowStart)
                        && !ev.getObservedAt().isAfter(windowEnd);

                return matchesIncident || inLookbackWindow;
            });

            if (hasCoOccurrence) {
                coOccurCount++;
            }
        }

        return coOccurCount;
    }
}
