package com.aurora.platform.telemetry.controller;

import com.aurora.platform.telemetry.dto.IngestTelemetryRequest;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.service.TelemetryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetryService telemetryService;

    public TelemetryController(TelemetryService telemetryService) {
        this.telemetryService = telemetryService;
    }

    @PostMapping
    public ResponseEntity<TelemetryEventResponse> ingestTelemetry(@Valid @RequestBody IngestTelemetryRequest request) {
        TelemetryEventResponse ingested = telemetryService.ingestTelemetry(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ingested);
    }

    @GetMapping("/resource/{resourceId}")
    public ResponseEntity<List<TelemetryEventResponse>> getTelemetryByResourceId(
            @PathVariable UUID resourceId,
            @RequestParam(required = false) String metricName) {
        List<TelemetryEventResponse> telemetryEvents = telemetryService.getTelemetryByResourceId(resourceId, metricName);
        return ResponseEntity.ok(telemetryEvents);
    }
}
