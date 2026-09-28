package com.aurora.platform.intelligence.rca.infrastructure;

import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.rca.application.IncidentEvidenceQuery;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class IncidentEvidenceQueryAdapter implements IncidentEvidenceQuery {

    private final IncidentRepository incidentRepository;
    private final IncidentAnomalyEvidenceRepository evidenceRepository;

    public IncidentEvidenceQueryAdapter(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository evidenceRepository) {
        this.incidentRepository = incidentRepository;
        this.evidenceRepository = evidenceRepository;
    }

    @Override
    public Optional<IncidentEntity> findIncident(UUID incidentId) {
        return incidentRepository.findById(incidentId);
    }

    @Override
    public boolean existsIncident(UUID incidentId) {
        return incidentRepository.existsById(incidentId);
    }

    @Override
    public List<IncidentAnomalyEvidenceEntity> findEvidenceForIncident(UUID incidentId) {
        return evidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId);
    }

    @Override
    public List<IncidentAnomalyEvidenceEntity> findEvidenceForResourceInWindow(UUID resourceId, Instant start, Instant end) {
        return evidenceRepository.findByResourceIdAndObservedAtBetweenOrderByObservedAtAsc(resourceId, start, end);
    }
}
