package com.aurora.platform.resource.controller;

import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.DuplicateResourceException;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.resource.dto.CreateResourceRequest;
import com.aurora.platform.resource.dto.ResourceResponse;
import com.aurora.platform.resource.entity.ResourceStatus;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResourceController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class ResourceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ResourceService resourceService;

    @Test
    @DisplayName("POST /api/v1/resources - Should return 201 Created with valid payload")
    void shouldCreateResourceAndReturn201() throws Exception {
        UUID id = UUID.randomUUID();
        CreateResourceRequest request = new CreateResourceRequest(
                "order-service",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-01.us-east.aurora.internal",
                Map.of("version", "1.4.2")
        );

        ResourceResponse response = new ResourceResponse(
                id,
                "order-service",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-01.us-east.aurora.internal",
                Map.of("version", "1.4.2"),
                Instant.now(),
                Instant.now()
        );

        when(resourceService.createResource(any(CreateResourceRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/resources")
                        .header("X-Correlation-ID", "corr-test-1234")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-ID", "corr-test-1234"))
                .andExpect(header().string("Location", containsString("/api/v1/resources/" + id)))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("order-service"))
                .andExpect(jsonPath("$.type").value("SERVICE"))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.environment").value("production"));
    }

    @Test
    @DisplayName("POST /api/v1/resources - Should succeed with default status HEALTHY when status is omitted")
    void shouldCreateResourceWithoutStatusAndDefaultToHealthy() throws Exception {
        UUID id = UUID.randomUUID();
        String jsonPayload = """
                {
                  "name": "aurora-postgres",
                  "type": "DATABASE",
                  "environment": "development",
                  "host": "localhost"
                }
                """;

        ResourceResponse response = new ResourceResponse(
                id,
                "aurora-postgres",
                ResourceType.DATABASE,
                ResourceStatus.HEALTHY,
                "development",
                "localhost",
                Collections.emptyMap(),
                Instant.now(),
                Instant.now()
        );

        when(resourceService.createResource(any(CreateResourceRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("aurora-postgres"))
                .andExpect(jsonPath("$.type").value("DATABASE"))
                .andExpect(jsonPath("$.status").value("HEALTHY"))
                .andExpect(jsonPath("$.environment").value("development"))
                .andExpect(jsonPath("$.host").value("localhost"));
    }

    @Test
    @DisplayName("POST /api/v1/resources - Should return 400 Bad Request on validation failure")
    void shouldReturn400OnValidationFailure() throws Exception {
        // Missing name, host, and environment
        CreateResourceRequest invalidRequest = new CreateResourceRequest(
                "",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "",
                "",
                null
        );

        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details", notNullValue()))
                .andExpect(jsonPath("$.traceId", notNullValue()));
    }

    @Test
    @DisplayName("POST /api/v1/resources - Should return 400 when type is invalid")
    void shouldReturn400WhenTypeIsInvalid() throws Exception {
        String invalidJson = """
                {
                  "name": "valid-name",
                  "type": "NON_EXISTENT_TYPE",
                  "environment": "production",
                  "host": "host-01"
                }
                """;

        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST /api/v1/resources - Should return 409 Conflict when resource already exists")
    void shouldReturn409OnDuplicate() throws Exception {
        CreateResourceRequest request = new CreateResourceRequest(
                "order-service",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "srv-01",
                null
        );

        when(resourceService.createResource(any(CreateResourceRequest.class)))
                .thenThrow(new DuplicateResourceException("Resource with name 'order-service' already exists in environment 'production'"));

        mockMvc.perform(post("/api/v1/resources")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    @DisplayName("GET /api/v1/resources/{id} - Should return 200 OK when found")
    void shouldReturn200WhenResourceFound() throws Exception {
        UUID id = UUID.randomUUID();
        ResourceResponse response = new ResourceResponse(
                id,
                "payment-service",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "pay-01",
                Collections.emptyMap(),
                Instant.now(),
                Instant.now()
        );

        when(resourceService.getResourceById(id)).thenReturn(response);

        mockMvc.perform(get("/api/v1/resources/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("payment-service"));
    }

    @Test
    @DisplayName("GET /api/v1/resources/{id} - Should return 404 Not Found when missing")
    void shouldReturn404WhenResourceMissing() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(resourceService.getResourceById(missingId))
                .thenThrow(new ResourceNotFoundException("Resource with ID '" + missingId + "' not found"));

        mockMvc.perform(get("/api/v1/resources/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("not found")));
    }

    @Test
    @DisplayName("GET /api/v1/resources - Should return 200 OK with list")
    void shouldReturnResourceList() throws Exception {
        ResourceResponse response = new ResourceResponse(
                UUID.randomUUID(),
                "cache-cluster",
                ResourceType.SERVICE,
                ResourceStatus.HEALTHY,
                "production",
                "redis-node-01",
                Collections.emptyMap(),
                Instant.now(),
                Instant.now()
        );

        when(resourceService.getAllResources(null, null, null)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/resources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("cache-cluster"));
    }

    @Test
    @DisplayName("GET /favicon.ico - Should return 404 NOT_FOUND with RESOURCE_NOT_FOUND error response")
    void shouldReturn404ForMissingStaticResource() throws Exception {
        mockMvc.perform(get("/favicon.ico"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/favicon.ico"))
                .andExpect(jsonPath("$.traceId").exists());
    }
}
