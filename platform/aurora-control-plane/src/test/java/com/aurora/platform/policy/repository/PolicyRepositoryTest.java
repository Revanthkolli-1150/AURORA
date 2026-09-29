package com.aurora.platform.policy.repository;

import com.aurora.platform.policy.entity.PolicyEntity;
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
class PolicyRepositoryTest {

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("Should persist policy with generated UUID and query by enabled/resourceType")
    void shouldPersistAndQueryPolicy() {
        PolicyEntity p1 = PolicyEntity.builder()
                .name("Database Connection Limit")
                .targetResourceType(ResourceType.DATABASE)
                .metricName("db_connection_utilization")
                .threshold(85.0)
                .action("SCALE_POOL")
                .enabled(true)
                .build();

        PolicyEntity p2 = PolicyEntity.builder()
                .name("Database Disk Space")
                .targetResourceType(ResourceType.DATABASE)
                .metricName("disk_utilization")
                .threshold(90.0)
                .action("EXPAND_STORAGE")
                .enabled(false)
                .build();

        PolicyEntity p3 = PolicyEntity.builder()
                .name("Container Memory Ceiling")
                .targetResourceType(ResourceType.CONTAINER)
                .metricName("memory_utilization")
                .threshold(80.0)
                .action("RESTART_POD")
                .enabled(true)
                .build();

        policyRepository.saveAll(List.of(p1, p2, p3));
        entityManager.flush();
        entityManager.clear();

        List<PolicyEntity> activeDatabasePolicies = policyRepository
                .findByTargetResourceTypeAndEnabledTrue(ResourceType.DATABASE);
        assertThat(activeDatabasePolicies).hasSize(1);
        assertThat(activeDatabasePolicies.get(0).getName()).isEqualTo("Database Connection Limit");

        List<PolicyEntity> allDatabasePolicies = policyRepository
                .findByTargetResourceType(ResourceType.DATABASE);
        assertThat(allDatabasePolicies).hasSize(2);

        List<PolicyEntity> allActive = policyRepository.findByEnabledTrue();
        assertThat(allActive).hasSize(2);
    }

    @Test
    @DisplayName("Should find policy by ID")
    void shouldFindById() {
        PolicyEntity policy = PolicyEntity.builder()
                .name("CPU Throttling Policy")
                .targetResourceType(ResourceType.POD)
                .metricName("cpu_usage")
                .threshold(95.0)
                .action("SCALE_OUT")
                .enabled(true)
                .build();

        PolicyEntity saved = policyRepository.save(policy);
        entityManager.flush();
        entityManager.clear();

        Optional<PolicyEntity> found = policyRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("CPU Throttling Policy");
        assertThat(found.get().getThreshold()).isEqualTo(95.0);
    }
}
