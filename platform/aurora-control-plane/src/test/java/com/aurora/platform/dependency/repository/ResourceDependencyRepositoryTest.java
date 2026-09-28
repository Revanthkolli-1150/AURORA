package com.aurora.platform.dependency.repository;

import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResourceDependencyRepositoryTest {

    @Autowired
    private ResourceDependencyRepository dependencyRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ResourceEntity createAndPersistResource(String name) {
        ResourceEntity resource = ResourceEntity.builder()
                .name(name)
                .type(ResourceType.SERVICE)
                .status(ResourceStatus.HEALTHY)
                .environment("development")
                .host("host-" + name)
                .build();
        return entityManager.persistAndFlush(resource);
    }

    @Test
    @DisplayName("Should persist and retrieve outgoing and incoming dependencies")
    void shouldPersistAndRetrieveDependencies() {
        ResourceEntity frontend = createAndPersistResource("frontend-" + UUID.randomUUID());
        ResourceEntity api = createAndPersistResource("api-" + UUID.randomUUID());
        ResourceEntity postgres = createAndPersistResource("postgres-" + UUID.randomUUID());

        ResourceDependencyEntity dep1 = ResourceDependencyEntity.builder()
                .sourceResourceId(frontend.getId())
                .targetResourceId(api.getId())
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();

        ResourceDependencyEntity dep2 = ResourceDependencyEntity.builder()
                .sourceResourceId(api.getId())
                .targetResourceId(postgres.getId())
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();

        dependencyRepository.save(dep1);
        dependencyRepository.save(dep2);
        entityManager.flush();
        entityManager.clear();

        // Check outgoing dependencies for frontend
        List<ResourceDependencyEntity> frontendDeps = dependencyRepository
                .findBySourceResourceIdOrderByCreatedAtAsc(frontend.getId());
        assertThat(frontendDeps).hasSize(1);
        assertThat(frontendDeps.get(0).getTargetResourceId()).isEqualTo(api.getId());

        // Check outgoing dependencies for api
        List<ResourceDependencyEntity> apiDeps = dependencyRepository
                .findBySourceResourceIdOrderByCreatedAtAsc(api.getId());
        assertThat(apiDeps).hasSize(1);
        assertThat(apiDeps.get(0).getTargetResourceId()).isEqualTo(postgres.getId());

        // Check incoming dependents for api
        List<ResourceDependencyEntity> apiDependents = dependencyRepository
                .findByTargetResourceIdOrderByCreatedAtAsc(api.getId());
        assertThat(apiDependents).hasSize(1);
        assertThat(apiDependents.get(0).getSourceResourceId()).isEqualTo(frontend.getId());

        // Check incoming dependents for postgres
        List<ResourceDependencyEntity> postgresDependents = dependencyRepository
                .findByTargetResourceIdOrderByCreatedAtAsc(postgres.getId());
        assertThat(postgresDependents).hasSize(1);
        assertThat(postgresDependents.get(0).getSourceResourceId()).isEqualTo(api.getId());
    }

    @Test
    @DisplayName("TEST 13: Database unique constraint prevents duplicate directed relationships")
    void shouldEnforceUniqueConstraintOnDuplicateRelationship() {
        ResourceEntity resA = createAndPersistResource("resA-" + UUID.randomUUID());
        ResourceEntity resB = createAndPersistResource("resB-" + UUID.randomUUID());

        ResourceDependencyEntity dep1 = ResourceDependencyEntity.builder()
                .sourceResourceId(resA.getId())
                .targetResourceId(resB.getId())
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();
        dependencyRepository.saveAndFlush(dep1);

        ResourceDependencyEntity dep2 = ResourceDependencyEntity.builder()
                .sourceResourceId(resA.getId())
                .targetResourceId(resB.getId())
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();

        assertThatThrownBy(() -> dependencyRepository.saveAndFlush(dep2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TEST 14: Database self-dependency check constraint rejects self dependency")
    void shouldEnforceSelfDependencyCheckConstraint() {
        ResourceEntity resA = createAndPersistResource("resSelf-" + UUID.randomUUID());

        ResourceDependencyEntity selfDep = ResourceDependencyEntity.builder()
                .sourceResourceId(resA.getId())
                .targetResourceId(resA.getId())
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();

        assertThatThrownBy(() -> dependencyRepository.saveAndFlush(selfDep))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TEST 12: Resource deletion cascades and deletes associated dependencies")
    void shouldCascadeDeleteDependenciesWhenResourceDeleted() {
        ResourceEntity source = createAndPersistResource("cascade-src-" + UUID.randomUUID());
        ResourceEntity target = createAndPersistResource("cascade-tgt-" + UUID.randomUUID());

        ResourceDependencyEntity dep = ResourceDependencyEntity.builder()
                .sourceResourceId(source.getId())
                .targetResourceId(target.getId())
                .dependencyType(DependencyType.DEPENDS_ON)
                .build();
        dependencyRepository.saveAndFlush(dep);

        assertThat(dependencyRepository.findBySourceResourceIdOrderByCreatedAtAsc(source.getId())).hasSize(1);

        // Delete source resource
        entityManager.remove(source);
        entityManager.flush();
        entityManager.clear();

        // Verify dependency was cascade-deleted
        assertThat(dependencyRepository.findByTargetResourceIdOrderByCreatedAtAsc(target.getId())).isEmpty();
    }
}
