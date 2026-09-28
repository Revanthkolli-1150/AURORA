package com.aurora.platform.dependency.controller;

import com.aurora.platform.common.web.CorrelationIdFilter;
import com.aurora.platform.common.config.JacksonConfig;
import com.aurora.platform.common.exception.ConflictException;
import com.aurora.platform.common.exception.GlobalExceptionHandler;
import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.entity.DependencyType;
import com.aurora.platform.dependency.service.ResourceDependencyService;
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
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResourceDependencyController.class)
@Import({GlobalExceptionHandler.class, CorrelationIdFilter.class, JacksonConfig.class})
class ResourceDependencyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ResourceDependencyService dependencyService;

    @Test
    @DisplayName("POST /dependencies - Should return 201 Created with Location header on valid dependency")
    void shouldReturn201CreatedOnValidDependency() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(targetId, DependencyType.DEPENDS_ON);
        ResourceDependencyResponse response = new ResourceDependencyResponse(
                dependencyId, sourceId, targetId, DependencyType.DEPENDS_ON, Instant.now());

        when(dependencyService.createDependency(eq(sourceId), any(CreateResourceDependencyRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/resources/{resourceId}/dependencies", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/resources/" + sourceId + "/dependencies/" + dependencyId)))
                .andExpect(jsonPath("$.id").value(dependencyId.toString()))
                .andExpect(jsonPath("$.sourceResourceId").value(sourceId.toString()))
                .andExpect(jsonPath("$.targetResourceId").value(targetId.toString()))
                .andExpect(jsonPath("$.dependencyType").value("DEPENDS_ON"));
    }

    @Test
    @DisplayName("POST /dependencies - Missing targetResourceId should return 400 VALIDATION_ERROR")
    void shouldReturn400WhenTargetResourceIdMissing() throws Exception {
        UUID sourceId = UUID.randomUUID();
        String json = "{\"dependencyType\":\"DEPENDS_ON\"}";

        mockMvc.perform(post("/api/v1/resources/{resourceId}/dependencies", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /dependencies - Invalid dependencyType should return 400 MALFORMED_REQUEST")
    void shouldReturn400WhenInvalidDependencyType() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        String json = "{\"targetResourceId\":\"" + targetId + "\",\"dependencyType\":\"INVALID_TYPE\"}";

        mockMvc.perform(post("/api/v1/resources/{resourceId}/dependencies", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("POST /dependencies - Self dependency should return 400 VALIDATION_ERROR")
    void shouldReturn400WhenSelfDependency() throws Exception {
        UUID sourceId = UUID.randomUUID();
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(sourceId, DependencyType.DEPENDS_ON);

        when(dependencyService.createDependency(eq(sourceId), any(CreateResourceDependencyRequest.class)))
                .thenThrow(new ValidationException("A resource cannot depend on itself"));

        mockMvc.perform(post("/api/v1/resources/{resourceId}/dependencies", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("A resource cannot depend on itself"));
    }

    @Test
    @DisplayName("POST /dependencies - Duplicate dependency should return 409 CONFLICT")
    void shouldReturn409WhenDuplicateDependency() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        CreateResourceDependencyRequest request = new CreateResourceDependencyRequest(targetId, DependencyType.DEPENDS_ON);

        when(dependencyService.createDependency(eq(sourceId), any(CreateResourceDependencyRequest.class)))
                .thenThrow(new ConflictException("Dependency already exists"));

        mockMvc.perform(post("/api/v1/resources/{resourceId}/dependencies", sourceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    @DisplayName("GET /dependencies - Should return 200 OK with list of outgoing dependencies")
    void shouldReturnOutgoingDependenciesList() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        ResourceDependencyResponse dep = new ResourceDependencyResponse(
                UUID.randomUUID(), sourceId, targetId, DependencyType.DEPENDS_ON, Instant.now());

        when(dependencyService.getDependencies(sourceId)).thenReturn(List.of(dep));

        mockMvc.perform(get("/api/v1/resources/{resourceId}/dependencies", sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceResourceId").value(sourceId.toString()))
                .andExpect(jsonPath("$[0].targetResourceId").value(targetId.toString()));
    }

    @Test
    @DisplayName("GET /dependents - Should return 200 OK with list of incoming dependents")
    void shouldReturnIncomingDependentsList() throws Exception {
        UUID targetId = UUID.randomUUID();
        UUID callerId = UUID.randomUUID();
        ResourceDependencyResponse dep = new ResourceDependencyResponse(
                UUID.randomUUID(), callerId, targetId, DependencyType.DEPENDS_ON, Instant.now());

        when(dependencyService.getDependents(targetId)).thenReturn(List.of(dep));

        mockMvc.perform(get("/api/v1/resources/{resourceId}/dependents", targetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sourceResourceId").value(callerId.toString()))
                .andExpect(jsonPath("$[0].targetResourceId").value(targetId.toString()));
    }

    @Test
    @DisplayName("DELETE /dependencies/{dependencyId} - Should return 204 No Content on success")
    void shouldReturn204OnSuccessfulDeletion() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        doNothing().when(dependencyService).deleteDependency(sourceId, dependencyId);

        mockMvc.perform(delete("/api/v1/resources/{resourceId}/dependencies/{dependencyId}", sourceId, dependencyId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /dependencies/{dependencyId} - Missing dependency should return 404 RESOURCE_NOT_FOUND")
    void shouldReturn404WhenDeletingMissingDependency() throws Exception {
        UUID sourceId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        doThrow(new ResourceNotFoundException("Dependency not found"))
                .when(dependencyService).deleteDependency(sourceId, dependencyId);

        mockMvc.perform(delete("/api/v1/resources/{resourceId}/dependencies/{dependencyId}", sourceId, dependencyId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }
}
