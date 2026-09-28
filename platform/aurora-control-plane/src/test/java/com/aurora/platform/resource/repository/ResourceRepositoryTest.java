package com.aurora.platform.resource.repository;

import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResourceRepositoryTest {

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Should persist resource with auto-generated UUID and timestamps")
    void shouldPersistResourceWithGeneratedIdAndTimestamps() {
        ResourceEntity entity = ResourceEntity.builder()
                .name("redis-cache-01")
                .type(ResourceType.SERVICE)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("redis-master.internal")
                .metadata("{\"cluster\":\"prod-east\"}")
                .build();

        ResourceEntity saved = resourceRepository.save(entity);
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<ResourceEntity> found = resourceRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("redis-cache-01");
        assertThat(found.get().getType()).isEqualTo(ResourceType.SERVICE);
        assertThat(found.get().getStatus()).isEqualTo(ResourceStatus.HEALTHY);
        assertThat(found.get().getEnvironment()).isEqualTo("production");
    }

    @Test
    @DisplayName("Should find resource by name and environment")
    void shouldFindByNameAndEnvironment() {
        ResourceEntity entity = ResourceEntity.builder()
                .name("aurora-postgres")
                .type(ResourceType.DATABASE)
                .status(ResourceStatus.HEALTHY)
                .environment("development")
                .host("localhost")
                .build();

        resourceRepository.save(entity);
        entityManager.flush();

        Optional<ResourceEntity> result = resourceRepository.findByNameAndEnvironment("aurora-postgres", "development");
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("aurora-postgres");

        boolean exists = resourceRepository.existsByNameAndEnvironment("aurora-postgres", "development");
        assertThat(exists).isTrue();

        boolean notExists = resourceRepository.existsByNameAndEnvironment("aurora-postgres", "production");
        assertThat(notExists).isFalse();
    }

    @Test
    @DisplayName("Should query resources by environment, type, and status")
    void shouldQueryByEnvironmentTypeAndStatus() {
        ResourceEntity r1 = ResourceEntity.builder()
                .name("web-frontend-01")
                .type(ResourceType.APPLICATION)
                .status(ResourceStatus.HEALTHY)
                .environment("production")
                .host("web-01.internal")
                .build();

        ResourceEntity r2 = ResourceEntity.builder()
                .name("web-frontend-02")
                .type(ResourceType.APPLICATION)
                .status(ResourceStatus.DEGRADED)
                .environment("staging")
                .host("web-02.internal")
                .build();

        resourceRepository.saveAll(List.of(r1, r2));
        entityManager.flush();

        List<ResourceEntity> prodList = resourceRepository.findByEnvironment("production");
        assertThat(prodList).anyMatch(r -> r.getName().equals("web-frontend-01"));

        List<ResourceEntity> appList = resourceRepository.findByType(ResourceType.APPLICATION);
        assertThat(appList).hasSizeGreaterThanOrEqualTo(2);

        List<ResourceEntity> degradedList = resourceRepository.findByStatus(ResourceStatus.DEGRADED);
        assertThat(degradedList).anyMatch(r -> r.getName().equals("web-frontend-02"));
    }
}
