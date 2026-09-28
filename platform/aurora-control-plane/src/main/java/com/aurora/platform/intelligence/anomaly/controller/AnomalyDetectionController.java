package com.aurora.platform.intelligence.anomaly.controller;

import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.service.AnomalyDetectionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/intelligence/anomaly")
public class AnomalyDetectionController {

    private final AnomalyDetectionService anomalyDetectionService;

    public AnomalyDetectionController(AnomalyDetectionService anomalyDetectionService) {
        this.anomalyDetectionService = anomalyDetectionService;
    }

    @GetMapping("/resource/{resourceId}")
    public ResponseEntity<AnomalyDetectionResponse> evaluateAnomaly(
            @PathVariable UUID resourceId,
            @RequestParam String metricName,
            @RequestParam(required = false) Double value,
            @RequestParam(required = false) Double threshold) {

        AnomalyDetectionResponse response = anomalyDetectionService.evaluateAnomaly(
                resourceId,
                metricName,
                value,
                threshold
        );
        return ResponseEntity.ok(response);
    }
}
