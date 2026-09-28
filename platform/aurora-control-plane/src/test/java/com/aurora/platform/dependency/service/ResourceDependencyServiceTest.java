package com.aurora.platform.dependency.service;

import com.aurora.platform.common.exception.ConflictException;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceDependencyServiceTest {

    @Mock
    private ResourceDependencyRepository dependencyRepository;

    @Mock
    private ResourceService resourceService;

    @Captor
    private ArgumentCaptor<ResourceDependencyEntity> entityCaptor;

    private ResourceDependencyService service;

    private UUID sourceId;
    private UUID targetId;

    @BeforeEach
    void setUp() {
        service = new ResourceDependencyServiceImpl(dependencyRepository, resourceService);
        sourceId = UUID.randomUUID();
        targetId = UUID.randomUUID();
    }

    @Test
    @DisplayName("TEST 1 & 2: Create and persist valid dependency successfully")
    void shouldCreateDependencySuccessfully() {
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(targetId, DependencyType.DEPENDS_ON);
        UUID dependencyId = UUID.randomUUID();
        Instant now = Instant.now();

        when(resourceService.existsById(sourceId)).thenReturn(true);
        when(resourceService.existsById(targetId)).thenReturn(true);
        when(dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(
                sourceId, targetId, DependencyType.DEPENDS_ON)).thenReturn(false);

        when(dependencyRepository.save(any(ResourceDependencyEntity.class))).thenAnswer(invocation -> {
            ResourceDependencyEntity entity = invocation.getArgument(0);
            entity.setId(dependencyId);
            entity.setCreatedAt(now);
            return entity;
        });

        ResourceDependencyResponse response = service.createDependency(sourceId, request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(dependencyId);
        assertThat(response.sourceResourceId()).isEqualTo(sourceId);
        assertThat(response.targetResourceId()).isEqualTo(targetId);
        assertThat(response.dependencyType()).isEqualTo(DependencyType.DEPENDS_ON);
        assertThat(response.createdAt()).isEqualTo(now);

        verify(dependencyRepository).save(entityCaptor.capture());
        ResourceDependencyEntity saved = entityCaptor.getValue();
        assertThat(saved.getSourceResourceId()).isEqualTo(sourceId);
        assertThat(saved.getTargetResourceId()).isEqualTo(targetId);
        assertThat(saved.getDependencyType()).isEqualTo(DependencyType.DEPENDS_ON);
    }

    @Test
    @DisplayName("TEST 3: Unknown source resource throws 404 ResourceNotFoundException")
    void shouldThrowNotFoundWhenSourceResourceMissing() {
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(targetId, DependencyType.DEPENDS_ON);
        when(resourceService.existsById(sourceId)).thenReturn(false);

        assertThatThrownBy(() -> service.createDependency(sourceId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Source resource with ID '" + sourceId + "' not found");

        verify(dependencyRepository, never()).save(any());
    }

    @Test
    @DisplayName("TEST 4: Unknown target resource throws 404 ResourceNotFoundException")
    void shouldThrowNotFoundWhenTargetResourceMissing() {
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(targetId, DependencyType.DEPENDS_ON);
        when(resourceService.existsById(sourceId)).thenReturn(true);
        when(resourceService.existsById(targetId)).thenReturn(false);

        assertThatThrownBy(() -> service.createDependency(sourceId, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Target resource with ID '" + targetId + "' not found");

        verify(dependencyRepository, never()).save(any());
    }

    @Test
    @DisplayName("TEST 5: Self-dependency (A -> A) throws 400 ValidationException")
    void shouldThrowValidationExceptionOnSelfDependency() {
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(sourceId, DependencyType.DEPENDS_ON);
        when(resourceService.existsById(sourceId)).thenReturn(true);

        assertThatThrownBy(() -> service.createDependency(sourceId, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("A resource cannot depend on itself");

        verify(dependencyRepository, never()).save(any());
    }

    @Test
    @DisplayName("TEST 6: Duplicate dependency throws 409 ConflictException")
    void shouldThrowConflictExceptionOnDuplicateDependency() {
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(targetId, DependencyType.DEPENDS_ON);
        when(resourceService.existsById(sourceId)).thenReturn(true);
        when(resourceService.existsById(targetId)).thenReturn(true);
        when(dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(
                sourceId, targetId, DependencyType.DEPENDS_ON)).thenReturn(true);

        assertThatThrownBy(() -> service.createDependency(sourceId, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Dependency already exists from resource '" + sourceId + "' to resource '" + targetId + "'");

        verify(dependencyRepository, never()).save(any());
    }

    @Test
    @DisplayName("TEST 7: Reverse dependency (B -> A) is distinct and allowed")
    void shouldAllowReverseDependency() {
        // Reverse: targetId is now source, sourceId is now target
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(sourceId, DependencyType.DEPENDS_ON);
        when(resourceService.existsById(targetId)).thenReturn(true);
        when(resourceService.existsById(sourceId)).thenReturn(true);
        when(dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(
                targetId, sourceId, DependencyType.DEPENDS_ON)).thenReturn(false);

        when(dependencyRepository.save(any(ResourceDependencyEntity.class))).thenAnswer(invocation -> {
            ResourceDependencyEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(Instant.now());
            return entity;
        });

        ResourceDependencyResponse response = service.createDependency(targetId, request);
        assertThat(response).isNotNull();
        assertThat(response.sourceResourceId()).isEqualTo(targetId);
        assertThat(response.targetResourceId()).isEqualTo(sourceId);
    }

    @Test
    @DisplayName("TEST 8: Get dependencies returns only outgoing edges (source = resourceId)")
    void shouldReturnOnlyOutgoingDependencies() {
        when(resourceService.existsById(sourceId)).thenReturn(true);

        UUID targetB = UUID.randomUUID();
        UUID targetC = UUID.randomUUID();

        ResourceDependencyEntity depB = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(sourceId).targetResourceId(targetB)
                .dependencyType(DependencyType.DEPENDS_ON).createdAt(Instant.now()).build();
        ResourceDependencyEntity depC = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(sourceId).targetResourceId(targetC)
                .dependencyType(DependencyType.DEPENDS_ON).createdAt(Instant.now().plusSeconds(1)).build();

        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(sourceId))
                .thenReturn(List.of(depB, depC));

        List<ResourceDependencyResponse> result = service.getDependencies(sourceId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).targetResourceId()).isEqualTo(targetB);
        assertThat(result.get(1).targetResourceId()).isEqualTo(targetC);
    }

    @Test
    @DisplayName("TEST 9: Get dependents returns only incoming edges (target = resourceId)")
    void shouldReturnOnlyIncomingDependents() {
        when(resourceService.existsById(targetId)).thenReturn(true);

        UUID callerA = UUID.randomUUID();
        UUID callerC = UUID.randomUUID();

        ResourceDependencyEntity depA = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(callerA).targetResourceId(targetId)
                .dependencyType(DependencyType.DEPENDS_ON).createdAt(Instant.now()).build();
        ResourceDependencyEntity depC = ResourceDependencyEntity.builder()
                .id(UUID.randomUUID()).sourceResourceId(callerC).targetResourceId(targetId)
                .dependencyType(DependencyType.DEPENDS_ON).createdAt(Instant.now().plusSeconds(1)).build();

        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(targetId))
                .thenReturn(List.of(depA, depC));

        List<ResourceDependencyResponse> result = service.getDependents(targetId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).sourceResourceId()).isEqualTo(callerA);
        assertThat(result.get(1).sourceResourceId()).isEqualTo(callerC);
    }

    @Test
    @DisplayName("TEST 10: Delete dependency succeeds when dependency exists and belongs to source resource")
    void shouldDeleteDependencySuccessfully() {
        UUID dependencyId = UUID.randomUUID();
        ResourceDependencyEntity entity = ResourceDependencyEntity.builder()
                .id(dependencyId)
                .sourceResourceId(sourceId)
                .targetResourceId(targetId)
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();

        when(resourceService.existsById(sourceId)).thenReturn(true);
        when(dependencyRepository.findById(dependencyId)).thenReturn(Optional.of(entity));

        service.deleteDependency(sourceId, dependencyId);

        verify(dependencyRepository).delete(entity);
    }

    @Test
    @DisplayName("TEST 11: Delete dependency with mismatched source resource throws 404 and does not delete")
    void shouldThrowNotFoundWhenDependencyDoesNotBelongToSourceResource() {
        UUID wrongSourceId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        ResourceDependencyEntity entity = ResourceDependencyEntity.builder()
                .id(dependencyId)
                .sourceResourceId(sourceId) // actual source is sourceId, not wrongSourceId
                .targetResourceId(targetId)
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();

        when(resourceService.existsById(wrongSourceId)).thenReturn(true);
        when(dependencyRepository.findById(dependencyId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.deleteDependency(wrongSourceId, dependencyId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not belong to resource");

        verify(dependencyRepository, never()).delete(any());
    }

    @Test
    @DisplayName("TEST 17: Get dependencies on resource with no dependencies returns empty list")
    void shouldReturnEmptyListWhenNoDependencies() {
        when(resourceService.existsById(sourceId)).thenReturn(true);
        when(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(sourceId)).thenReturn(Collections.emptyList());

        List<ResourceDependencyResponse> result = service.getDependencies(sourceId);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("TEST 18: Get dependents on resource with no dependents returns empty list")
    void shouldReturnEmptyListWhenNoDependents() {
        when(resourceService.existsById(targetId)).thenReturn(true);
        when(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(targetId)).thenReturn(Collections.emptyList());

        List<ResourceDependencyResponse> result = service.getDependents(targetId);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("TEST 19: Linear chain A -> B -> C is supported")
    void shouldSupportLinearDependencyChain() {
        UUID resA = UUID.randomUUID();
        UUID resB = UUID.randomUUID();
        UUID resC = UUID.randomUUID();

        when(resourceService.existsById(resA)).thenReturn(true);
        when(resourceService.existsById(resB)).thenReturn(true);
        when(resourceService.existsById(resC)).thenReturn(true);

        when(dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(resA, resB, DependencyType.DEPENDS_ON)).thenReturn(false);
        when(dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(resB, resC, DependencyType.DEPENDS_ON)).thenReturn(false);

        when(dependencyRepository.save(any(ResourceDependencyEntity.class))).thenAnswer(invocation -> {
            ResourceDependencyEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(Instant.now());
            return entity;
        });

        ResourceDependencyResponse edge1 = service.createDependency(resA, new CreateResourceDependencyRequest(resB, DependencyType.DEPENDS_ON));
        ResourceDependencyResponse edge2 = service.createDependency(resB, new CreateResourceDependencyRequest(resC, DependencyType.DEPENDS_ON));

        assertThat(edge1.sourceResourceId()).isEqualTo(resA);
        assertThat(edge1.targetResourceId()).isEqualTo(resB);
        assertThat(edge2.sourceResourceId()).isEqualTo(resB);
        assertThat(edge2.targetResourceId()).isEqualTo(resC);
    }

    @Test
    @DisplayName("TEST 20: Multi-node cycle behavior A -> B -> C -> A is distinguished from self-loop and explicitly documented as deferred")
    void verifyMultiNodeCycleIsPermittedWhileSelfLoopIsProhibited() {
        UUID resA = UUID.randomUUID();
        UUID resB = UUID.randomUUID();
        UUID resC = UUID.randomUUID();

        // 1. Self-loop A -> A is strictly prohibited
        when(resourceService.existsById(resA)).thenReturn(true);
        assertThatThrownBy(() -> service.createDependency(resA, new CreateResourceDependencyRequest(resA, DependencyType.DEPENDS_ON)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("A resource cannot depend on itself");

        // 2. Multi-node edge C -> A closing A -> B -> C -> A is permitted at data layer in current phase
        // (Full multi-node DFS cycle detection is deferred to future phase)
        when(resourceService.existsById(resC)).thenReturn(true);
        when(dependencyRepository.existsBySourceResourceIdAndTargetResourceIdAndDependencyType(resC, resA, DependencyType.DEPENDS_ON)).thenReturn(false);
        when(dependencyRepository.save(any(ResourceDependencyEntity.class))).thenAnswer(invocation -> {
            ResourceDependencyEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            entity.setCreatedAt(Instant.now());
            return entity;
        });

        ResourceDependencyResponse closingEdge = service.createDependency(resC, new CreateResourceDependencyRequest(resA, DependencyType.DEPENDS_ON));
        assertThat(closingEdge.sourceResourceId()).isEqualTo(resC);
        assertThat(closingEdge.targetResourceId()).isEqualTo(resA);
    }
}
