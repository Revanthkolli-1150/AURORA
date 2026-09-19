package com.aurora.platform;

import com.aurora.platform.resources.dto.CreateResourceRequest;
import com.aurora.platform.resources.dto.ResourceResponse;
import com.aurora.platform.resources.entity.ResourceStatus;
import com.aurora.platform.resources.entity.ResourceType;
import com.aurora.platform.resources.service.ResourceService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PostgreSqlTestcontainersTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aurora_test")
            .withUsername("aurora_user")
            .withPassword("aurora_pass");

    @BeforeAll
    static void checkDockerAvailability() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker daemon is not running; skipping PostgreSqlTestcontainersTest"
        );
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (DockerClientFactory.instance().isDockerAvailable() && postgres.isRunning()) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
            registry.add("spring.flyway.enabled", () -> "true");
        }
    }

    @Autowired(required = false)
    private ResourceService resourceService;

    @Test
    @DisplayName("Should execute against PostgreSQL container when Docker is active")
    void testWithPostgresContainer() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable() && postgres.isRunning(),
                "Docker not running"
        );

        CreateResourceRequest request = new CreateResourceRequest(
                "postgres-container-test-resource",
                ResourceType.DATABASE,
                ResourceStatus.HEALTHY,
                "integration-test",
                "test-host-01",
                Map.of("testKey", "testValue")
        );

        ResourceResponse response = resourceService.createResource(request);
        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("postgres-container-test-resource");

        ResourceResponse fetched = resourceService.getResourceById(response.id());
        assertThat(fetched.name()).isEqualTo("postgres-container-test-resource");
    }
}
