package com.aurora.platform.resources.service;

import com.aurora.platform.common.exception.DuplicateResourceException;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.resources.dto.CreateResourceRequest;
import com.aurora.platform.resources.dto.ResourceResponse;
import com.aurora.platform.resources.entity.ResourceEntity;
import com.aurora.platform.resources.entity.ResourceStatus;
import com.aurora.platform.resources.entity.ResourceType;
import com.aurora.platform.resources.repository.ResourceRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ResourceServiceImpl implements ResourceService {

    private static final Logger log = LoggerFactory.getLogger(ResourceServiceImpl.class);

    private final ResourceRepository resourceRepository;
    private final ObjectMapper objectMapper;

    public ResourceServiceImpl(ResourceRepository resourceRepository, ObjectMapper objectMapper) {
        this.resourceRepository = resourceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ResourceResponse createResource(CreateResourceRequest request) {
        log.info("Creating resource '{}' of type {} in environment '{}'", request.name(), request.type(), request.environment());

        if (resourceRepository.existsByNameAndEnvironment(request.name(), request.environment())) {
            throw new DuplicateResourceException(
                    "Resource with name '" + request.name() + "' already exists in environment '" + request.environment() + "'"
            );
        }

        String metadataJson = null;
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.metadata());
            } catch (Exception e) {
                log.warn("Failed to serialize resource metadata to JSON", e);
            }
        }

        ResourceEntity entity = ResourceEntity.builder()
                .name(request.name().trim())
                .type(request.type())
                .status(request.status())
                .environment(request.environment().trim())
                .host(request.host().trim())
                .metadata(metadataJson)
                .build();

        ResourceEntity saved = resourceRepository.save(entity);
        log.info("Successfully created resource with ID: {}", saved.getId());
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ResourceResponse getResourceById(UUID id) {
        return resourceRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Resource with ID '" + id + "' not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResourceResponse> getAllResources(String environment, ResourceType type, ResourceStatus status) {
        List<ResourceEntity> entities;
        if (environment != null && !environment.isBlank()) {
            entities = resourceRepository.findByEnvironment(environment.trim());
        } else if (type != null) {
            entities = resourceRepository.findByType(type);
        } else if (status != null) {
            entities = resourceRepository.findByStatus(status);
        } else {
            entities = resourceRepository.findAll();
        }

        return entities.stream().map(this::mapToResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(UUID id) {
        return resourceRepository.existsById(id);
    }

    private ResourceResponse mapToResponse(ResourceEntity entity) {
        Map<String, Object> metadata = Collections.emptyMap();
        if (entity.getMetadata() != null && !entity.getMetadata().isBlank()) {
            try {
                metadata = objectMapper.readValue(entity.getMetadata(), new TypeReference<>() {});
            } catch (Exception e) {
                log.warn("Failed to deserialize resource metadata JSON for ID {}", entity.getId(), e);
            }
        }

        return new ResourceResponse(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getStatus(),
                entity.getEnvironment(),
                entity.getHost(),
                metadata,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
