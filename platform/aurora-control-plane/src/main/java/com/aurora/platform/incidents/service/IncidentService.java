package com.aurora.platform.incidents.service;

import com.aurora.platform.incidents.dto.CreateIncidentRequest;
import com.aurora.platform.incidents.dto.IncidentResponse;
import com.aurora.platform.incidents.entity.IncidentSeverity;
import com.aurora.platform.incidents.entity.IncidentStatus;

import java.util.List;
import java.util.UUID;

public interface IncidentService {

    IncidentResponse getIncidentById(UUID id);

    List<IncidentResponse> getAllIncidents(UUID resourceId, IncidentStatus status, IncidentSeverity severity);

    IncidentResponse createIncident(CreateIncidentRequest request);

    boolean existsById(UUID id);
}
