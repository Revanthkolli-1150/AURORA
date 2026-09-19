package com.aurora.platform.incidents.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incidents.dto.CreateIncidentRequest;
import com.aurora.platform.incidents.dto.IncidentResponse;
import com.aurora.platform.incidents.entity.IncidentEntity;
import com.aurora.platform.incidents.entity.IncidentSeverity;
import com.aurora.platform.incidents.entity.IncidentStatus;
import com.aurora.platform.incidents.repository.IncidentRepository;
import com.aurora.platform.resources.service.ResourceService;
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
    private final ResourceService resourceService;

    public IncidentServiceImpl(IncidentRepository incidentRepository, ResourceService resourceService) {
        this.incidentRepository = incidentRepository;
        this.resourceService = resourceService;
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
    public boolean existsById(UUID id) {
        return incidentRepository.existsById(id);
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
