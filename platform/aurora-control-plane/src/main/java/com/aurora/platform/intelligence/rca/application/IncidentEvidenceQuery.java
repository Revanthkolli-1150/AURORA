package com.aurora.platform.intelligence.rca.application;

import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application query port for accessing incident information and anomaly evidence required by RCA.
 */
public interface IncidentEvidenceQuery {

    Optional<IncidentEntity> findIncident(UUID incidentId);

    boolean existsIncident(UUID incidentId);

    List<IncidentAnomalyEvidenceEntity> findEvidenceForIncident(UUID incidentId);

    List<IncidentAnomalyEvidenceEntity> findEvidenceForResourceInWindow(UUID resourceId, Instant start, Instant end);
}
