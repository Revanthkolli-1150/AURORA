package com.aurora.platform.policy.controller;

import com.aurora.platform.policy.dto.CreatePolicyRequest;
import com.aurora.platform.policy.dto.PolicyResponse;
import com.aurora.platform.policy.service.PolicyService;
import com.aurora.platform.resource.entity.ResourceType;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @GetMapping
    public ResponseEntity<List<PolicyResponse>> getPolicies(
            @RequestParam(required = false) ResourceType targetResourceType,
            @RequestParam(required = false) Boolean enabledOnly) {
        List<PolicyResponse> policies = policyService.getAllPolicies(targetResourceType, enabledOnly);
        return ResponseEntity.ok(policies);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PolicyResponse> getPolicyById(@PathVariable UUID id) {
        PolicyResponse policy = policyService.getPolicyById(id);
        return ResponseEntity.ok(policy);
    }

    @PostMapping
    public ResponseEntity<PolicyResponse> createPolicy(@Valid @RequestBody CreatePolicyRequest request) {
        PolicyResponse created = policyService.createPolicy(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<PolicyResponse> updatePolicyStatus(
            @PathVariable UUID id,
            @RequestParam boolean enabled) {
        PolicyResponse updated = policyService.updatePolicyStatus(id, enabled);
        return ResponseEntity.ok(updated);
    }
}
