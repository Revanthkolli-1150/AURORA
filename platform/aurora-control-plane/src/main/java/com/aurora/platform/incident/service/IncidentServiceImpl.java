package com.aurora.platform.incident.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.resource.service.ResourceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class IncidentServiceImpl implements IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentServiceImpl.class);

    private final IncidentRepository incidentRepository;
    private final IncidentAnomalyEvidenceRepository evidenceRepository;
    private final ResourceService resourceService;

    @org.springframework.beans.factory.annotation.Autowired
    public IncidentServiceImpl(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository evidenceRepository,
            ResourceService resourceService) {
        this.incidentRepository = incidentRepository;
        this.evidenceRepository = evidenceRepository;
        this.resourceService = resourceService;
    }

    public IncidentServiceImpl(IncidentRepository incidentRepository, ResourceService resourceService) {
        this(incidentRepository, null, resourceService);
    }

    @Override
    @Transactional(readOnly = true)
    public IncidentResponse getIncidentById(UUID id) {
        return incidentRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Incident with ID '" + id + "' not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentResponse> getAllIncidents(UUID resourceId, IncidentStatus status, IncidentSeverity severity) {
        List<IncidentEntity> entities;
        if (resourceId != null && status != null) {
            entities = incidentRepository.findByResourceIdAndStatus(resourceId, status);
        } else if (resourceId != null) {
            entities = incidentRepository.findByResourceId(resourceId);
        } else if (status != null) {
            entities = incidentRepository.findByStatus(status);
        } else if (severity != null) {
            entities = incidentRepository.findBySeverity(severity);
        } else {
            entities = incidentRepository.findAllByOrderByDetectedAtDesc();
        }

        return entities.stream().map(this::mapToResponse).toList();
    }

    @Override
    @Transactional
    public IncidentResponse createIncident(CreateIncidentRequest request) {
        log.info("Creating incident '{}' for resource {}", request.title(), request.resourceId());

        if (!resourceService.existsById(request.resourceId())) {
            throw new ResourceNotFoundException("Resource with ID '" + request.resourceId() + "' not found");
        }

        Instant detectedAt = request.detectedAt() != null ? request.detectedAt() : Instant.now();

        IncidentEntity entity = IncidentEntity.builder()
                .resourceId(request.resourceId())
                .title(request.title().trim())
                .description(request.description())
                .severity(request.severity())
                .status(request.status())
                .confidence(request.confidence())
                .rootCause(request.rootCause())
                .detectedAt(detectedAt)
                .build();

        IncidentEntity saved = incidentRepository.save(entity);
        log.info("Successfully created incident with ID: {}", saved.getId());
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IncidentAnomalyEvidenceResponse> getEvidenceByIncidentId(UUID incidentId) {
        if (!incidentRepository.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident with ID '" + incidentId + "' not found");
        }
        return evidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)
                .stream()
                .map(this::mapToEvidenceResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        return incidentRepository.existsById(id);
    }

    @Override
    @Transactional
    public IncidentResponse updateIncidentStatus(UUID id, IncidentStatus targetStatus) {
        if (targetStatus == null) {
            throw new IllegalArgumentException("Target incident status cannot be null");
        }

        IncidentEntity incident = incidentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident with ID '" + id + "' not found"));

        incident.getStatus().validateTransitionTo(targetStatus);

        log.info("Transitioning incident {} status from {} to {}", id, incident.getStatus(), targetStatus);
        incident.setStatus(targetStatus);
        if (targetStatus == IncidentStatus.RESOLVED && incident.getResolvedAt() == null) {
            incident.setResolvedAt(Instant.now());
        }
        incident.setUpdatedAt(Instant.now());

        IncidentEntity updated = incidentRepository.save(incident);
        return mapToResponse(updated);
    }

    private IncidentAnomalyEvidenceResponse mapToEvidenceResponse(IncidentAnomalyEvidenceEntity entity) {
        return new IncidentAnomalyEvidenceResponse(
                entity.getId(),
                entity.getIncidentId(),
                entity.getResourceId(),
                entity.getMetricName(),
                entity.getObservedValue(),
                entity.getAnomalyScore(),
                entity.getZScore(),
                entity.getDetectionMethod(),
                entity.getObservedAt(),
                entity.getCreatedAt()
        );
    }

    private IncidentResponse mapToResponse(IncidentEntity entity) {
        return new IncidentResponse(
                entity.getId(),
                entity.getResourceId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getSeverity(),
                entity.getStatus(),
                entity.getConfidence(),
                entity.getRootCause(),
                entity.getDetectedAt(),
                entity.getResolvedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
