package com.aurora.platform.dependency.controller;

import com.aurora.platform.dependency.dto.CreateResourceDependencyRequest;
import com.aurora.platform.dependency.dto.ResourceDependencyResponse;
import com.aurora.platform.dependency.service.ResourceDependencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resources/{resourceId}")
public class ResourceDependencyController {

    private final ResourceDependencyService dependencyService;

    public ResourceDependencyController(ResourceDependencyService dependencyService) {
        this.dependencyService = dependencyService;
    }

    @PostMapping("/dependencies")
    public ResponseEntity<ResourceDependencyResponse> createDependency(
            @PathVariable UUID resourceId,
            @Valid @RequestBody CreateResourceDependencyRequest request) {

        ResourceDependencyResponse created = dependencyService.createDependency(resourceId, request);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{dependencyId}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/dependencies")
    public ResponseEntity<List<ResourceDependencyResponse>> getDependencies(
            @PathVariable UUID resourceId) {

        List<ResourceDependencyResponse> dependencies = dependencyService.getDependencies(resourceId);
        return ResponseEntity.ok(dependencies);
    }

    @GetMapping("/dependents")
    public ResponseEntity<List<ResourceDependencyResponse>> getDependents(
            @PathVariable UUID resourceId) {

        List<ResourceDependencyResponse> dependents = dependencyService.getDependents(resourceId);
        return ResponseEntity.ok(dependents);
    }

    @DeleteMapping("/dependencies/{dependencyId}")
    public ResponseEntity<Void> deleteDependency(
            @PathVariable UUID resourceId,
            @PathVariable UUID dependencyId) {

        dependencyService.deleteDependency(resourceId, dependencyId);
        return ResponseEntity.noContent().build();
    }
}
