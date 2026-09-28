package com.aurora.platform.incident.service;

import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;

import java.util.List;
import java.util.UUID;

public interface IncidentService {

    IncidentResponse getIncidentById(UUID id);

    List<IncidentResponse> getAllIncidents(UUID resourceId, IncidentStatus status, IncidentSeverity severity);

    IncidentResponse createIncident(CreateIncidentRequest request);

    List<IncidentAnomalyEvidenceResponse> getEvidenceByIncidentId(UUID incidentId);

    boolean existsById(UUID id);

    IncidentResponse updateIncidentStatus(UUID id, IncidentStatus targetStatus);
}
