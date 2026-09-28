package com.aurora.platform.resource.service;

import com.aurora.platform.common.exception.DuplicateResourceException;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.repository.ResourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {

    @Mock
    private ResourceRepository resourceRepository;

    private ObjectMapper objectMapper;
    private ResourceService resourceService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        resourceService = new ResourceServiceImpl(resourceRepository, objectMapper);
    }

    @Test
    @DisplayName("Should create resource successfully when it does not already exist")
    void shouldCreateResourceSuccessfully() {
        UUID id = UUID.randomUUID();
        CreateResourceRequest request = new CreateResourceRequest(
                "k8s-ingress-controller",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "edge-node-01.internal",
                Map.of("cluster", "prod-us-east-1", "tier", "edge")
        );

        when(resourceRepository.existsByNameAndEnvironment("k8s-ingress-controller", "production"))
                .thenReturn(false);

        ResourceEntity savedEntity = ResourceEntity.builder()
                .id(id)
                .name(request.name())
                .type(request.type())
                .status(request.status())
                .environment(request.environment())
                .host(request.host())
                .metadata("{\"cluster\":\"prod-us-east-1\",\"tier\":\"edge\"}")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(resourceRepository.save(any(ResourceEntity.class))).thenReturn(savedEntity);

        ResourceResponse response = resourceService.createResource(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("k8s-ingress-controller");
        assertThat(response.type()).isEqualTo(ResourceType.SERVICE);
        assertThat(response.status()).isEqualTo(ResourceStatus.HEALTHY);
        assertThat(response.environment()).isEqualTo("production");
        assertThat(response.metadata()).containsEntry("cluster", "prod-us-east-1");

        verify(resourceRepository).save(any(ResourceEntity.class));
    }

    @Test
    @DisplayName("Should throw DuplicateResourceException when resource with name and env exists")
    void shouldThrowDuplicateException() {
        CreateResourceRequest request = new CreateResourceRequest(
                "auth-service",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "staging",
                "host-01",
                null
        );

        when(resourceRepository.existsByNameAndEnvironment("auth-service", "staging")).thenReturn(true);

        assertThatThrownBy(() -> resourceService.createResource(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists in environment 'staging'");

        verify(resourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should retrieve resource by ID successfully")
    void shouldGetResourceById() {
        UUID id = UUID.randomUUID();
        ResourceEntity entity = ResourceEntity.builder()
                .id(id)
                .name("db-primary")
                .type(ResourceType.DATABASE)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("db-01.aws")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(resourceRepository.findById(id)).thenReturn(Optional.of(entity));

        ResourceResponse response = resourceService.getResourceById(id);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("db-primary");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when resource ID does not exist")
    void shouldThrowNotFoundWhenResourceMissing() {
        UUID missingId = UUID.randomUUID();
        when(resourceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resourceService.getResourceById(missingId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Resource with ID '" + missingId + "' not found");
    }

    @Test
    @DisplayName("Should list resources filtered by environment")
    void shouldListResourcesByEnvironment() {
        ResourceEntity entity = ResourceEntity.builder()
                .id(UUID.randomUUID())
                .name("app-backend")
                .type(ResourceType.APPLICATION)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("app-01")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(resourceRepository.findByEnvironment("production")).thenReturn(List.of(entity));

        List<ResourceResponse> results = resourceService.getAllResources("production", null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("app-backend");
    }

    @Test
    @DisplayName("Should create resource with default HEALTHY status when status is null")
    void shouldCreateResourceWithDefaultHealthyStatusWhenNull() {
        UUID id = UUID.randomUUID();
        CreateResourceRequest request = new CreateResourceRequest(
                "aurora-postgres",
                ResourceType.DATABASE,
                "development",
                "localhost"
        );

        when(resourceRepository.existsByNameAndEnvironment("aurora-postgres", "development")).thenReturn(false);

        ResourceEntity savedEntity = ResourceEntity.builder()
                .id(id)
                .name("aurora-postgres")
                .type(ResourceType.DATABASE)
                .status(ResourceStatus.HEALTHY)
                .environment("development")
                .host("localhost")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(resourceRepository.save(any(ResourceEntity.class))).thenReturn(savedEntity);

        ResourceResponse response = resourceService.createResource(request);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(ResourceStatus.HEALTHY);
    }

    @Test
    @DisplayName("Should list resources with combined filters")
    void shouldFilterResourcesByCombinedCriteria() {
        ResourceEntity r1 = ResourceEntity.builder()
                .id(UUID.randomUUID())
                .name("db-prod-primary")
                .type(ResourceType.DATABASE)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("db-01.internal")
                .build();

        ResourceEntity r2 = ResourceEntity.builder()
                .id(UUID.randomUUID())
                .name("db-prod-replica")
                .type(ResourceType.DATABASE)
                .status(ResourceStatus.DEGRADED)
                .environment("production")
                .host("db-02.internal")
                .build();

        when(resourceRepository.findAll()).thenReturn(List.of(r1, r2));

        List<ResourceResponse> results = resourceService.getAllResources("production", ResourceType.DATABASE, ResourceStatus.HEALTHY);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("db-prod-primary");
    }
}
