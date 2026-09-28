package com.aurora.platform.incident.service;

import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.incident.config.IncidentCorrelationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class IncidentCorrelationServiceImpl implements IncidentCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(IncidentCorrelationServiceImpl.class);

    /**
     * Active incident states eligible to receive correlated anomalies.
     * Terminal states (RESOLVED, FAILED) must never receive new anomalies.
     */
    public static final Set<IncidentStatus> ACTIVE_STATUSES = Set.of(
            IncidentStatus.DETECTED,
            IncidentStatus.INVESTIGATING,
            IncidentStatus.DIAGNOSED,
            IncidentStatus.RECOVERING,
            IncidentStatus.VERIFYING
    );

    private final IncidentRepository incidentRepository;
    private final IncidentAnomalyEvidenceRepository evidenceRepository;
    private final IncidentCorrelationProperties properties;
    private final ResourceLockRegistry lockRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    public IncidentCorrelationServiceImpl(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository evidenceRepository,
            IncidentCorrelationProperties properties,
            ResourceLockRegistry lockRegistry) {
        this.incidentRepository = incidentRepository;
        this.evidenceRepository = evidenceRepository;
        this.properties = properties;
        this.lockRegistry = lockRegistry;
    }

    public IncidentCorrelationServiceImpl(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository evidenceRepository,
            IncidentCorrelationProperties properties) {
        this(incidentRepository, evidenceRepository, properties, new ResourceLockRegistry());
    }

    @Override
    @Transactional
    public IncidentResponse correlateAnomaly(AnomalyDetectionResponse anomaly) {
        if (anomaly == null) {
            log.warn("Cannot correlate null anomaly");
            return null;
        }

        if (anomaly.status() != AnomalyStatus.ANOMALOUS) {
            log.debug("Skipping non-anomalous observation for resource {} (status: {})",
                    anomaly.resourceId(), anomaly.status());
            return null;
        }

        UUID resourceId = anomaly.resourceId();
        Instant anomalyTime = anomaly.evaluatedAt() != null ? anomaly.evaluatedAt() : Instant.now();

        // Concurrency guarantee: Bounded JVM-local lock serialization per resource ID.
        // Multiple threads correlating for the same resource are serialized, preventing conflicting active incidents.
        // Threads correlating for different resources proceed concurrently.
        return lockRegistry.executeWithLock(resourceId, () -> {
            List<IncidentEntity> activeIncidents = incidentRepository
                    .findByResourceIdAndStatusInOrderByDetectedAtDesc(resourceId, ACTIVE_STATUSES);

            IncidentEntity targetIncident = null;

            for (IncidentEntity candidate : activeIncidents) {
                Optional<IncidentAnomalyEvidenceEntity> latestEvidence = evidenceRepository
                        .findFirstByIncidentIdOrderByObservedAtDesc(candidate.getId());

                Instant latestTime = latestEvidence
                        .map(IncidentAnomalyEvidenceEntity::getObservedAt)
                        .orElse(candidate.getDetectedAt());

                long diffSeconds = Math.abs(Duration.between(latestTime, anomalyTime).getSeconds());

                if (diffSeconds <= properties.getCorrelationWindowSeconds()) {
                    targetIncident = candidate;
                    break;
                }
            }

            if (targetIncident != null) {
                log.info("Correlating anomaly for metric '{}' on resource {} to existing active incident {}",
                        anomaly.metricName(), resourceId, targetIncident.getId());

                return attachEvidenceToExistingIncident(targetIncident, anomaly, anomalyTime);
            } else {
                log.info("Creating new incident for anomalous metric '{}' on resource {}",
                        anomaly.metricName(), resourceId);

                return createNewIncidentWithEvidence(anomaly, anomalyTime);
            }
        });
    }

    private IncidentResponse attachEvidenceToExistingIncident(
            IncidentEntity incident,
            AnomalyDetectionResponse anomaly,
            Instant anomalyTime) {

        IncidentAnomalyEvidenceEntity evidence = IncidentAnomalyEvidenceEntity.builder()
                .incidentId(incident.getId())
                .resourceId(anomaly.resourceId())
                .metricName(anomaly.metricName())
                .observedValue(anomaly.currentValue())
                .anomalyScore(anomaly.anomalyScore())
                .zScore(anomaly.zScore())
                .detectionMethod(anomaly.detectionMethod())
                .observedAt(anomalyTime)
                .build();

        evidenceRepository.save(evidence);

        // Deterministic severity escalation
        IncidentSeverity newSeverity = mapSeverity(anomaly.anomalyScore());
        if (isHigherSeverity(newSeverity, incident.getSeverity())) {
            log.info("Escalating incident {} severity from {} to {} due to new anomaly score {}",
                    incident.getId(), incident.getSeverity(), newSeverity, anomaly.anomalyScore());
            incident.setSeverity(newSeverity);
        }

        // Title and description update for multiple reliability anomalies
        if (!incident.getTitle().contains(anomaly.metricName())) {
            incident.setTitle("Multiple reliability anomalies detected");
        }

        String evidenceSnippet = String.format("\nAdditional anomalous %s observation of %.2f detected (z-score: %s).",
                anomaly.metricName(),
                anomaly.currentValue(),
                anomaly.zScore() != null ? String.format("%.2f", anomaly.zScore()) : "N/A");

        if (incident.getDescription() != null) {
            incident.setDescription(incident.getDescription() + evidenceSnippet);
        } else {
            incident.setDescription(evidenceSnippet.trim());
        }

        incident.setUpdatedAt(Instant.now());
        IncidentEntity updated = incidentRepository.save(incident);

        return mapToResponse(updated);
    }

    private IncidentResponse createNewIncidentWithEvidence(
            AnomalyDetectionResponse anomaly,
            Instant anomalyTime) {

        IncidentSeverity severity = mapSeverity(anomaly.anomalyScore());

        String title = formatMetricTitle(anomaly.metricName());
        String zScoreText = anomaly.zScore() != null ? String.format(" with z-score %.2f", anomaly.zScore()) : "";
        String description = String.format(
                "An anomalous %s observation of %.2f was detected for resource %s%s.",
                anomaly.metricName(),
                anomaly.currentValue(),
                anomaly.resourceId(),
                zScoreText
        );

        IncidentEntity incident = IncidentEntity.builder()
                .resourceId(anomaly.resourceId())
                .title(title)
                .description(description)
                .severity(severity)
                .status(IncidentStatus.DETECTED)
                .confidence(null)   // Intentionally left null until RCA is implemented
                .rootCause(null)     // Intentionally left null: an anomaly is not a root cause
                .detectedAt(anomalyTime)
                .build();

        IncidentEntity savedIncident = incidentRepository.save(incident);

        IncidentAnomalyEvidenceEntity evidence = IncidentAnomalyEvidenceEntity.builder()
                .incidentId(savedIncident.getId())
                .resourceId(anomaly.resourceId())
                .metricName(anomaly.metricName())
                .observedValue(anomaly.currentValue())
                .anomalyScore(anomaly.anomalyScore())
                .zScore(anomaly.zScore())
                .detectionMethod(anomaly.detectionMethod())
                .observedAt(anomalyTime)
                .build();

        evidenceRepository.save(evidence);

        log.info("Created new incident {} with initial anomaly evidence {}",
                savedIncident.getId(), evidence.getId());

        return mapToResponse(savedIncident);
    }

    /**
     * Deterministic mapping from anomaly score to incident severity:
     * - anomalyScore < 0.5  -> LOW
     * - 0.5 <= score < 0.8  -> MEDIUM
     * - 0.8 <= score < 0.95 -> HIGH
     * - score >= 0.95       -> CRITICAL
     */
    public IncidentSeverity mapSeverity(Double anomalyScore) {
        if (anomalyScore == null || anomalyScore < 0.5) {
            return IncidentSeverity.LOW;
        } else if (anomalyScore < 0.8) {
            return IncidentSeverity.MEDIUM;
        } else if (anomalyScore < 0.95) {
            return IncidentSeverity.HIGH;
        } else {
            return IncidentSeverity.CRITICAL;
        }
    }

    private boolean isHigherSeverity(IncidentSeverity s1, IncidentSeverity s2) {
        return severityRank(s1) > severityRank(s2);
    }

    private int severityRank(IncidentSeverity s) {
        if (s == null) return 0;
        return switch (s) {
            case INFO -> 1;
            case LOW -> 2;
            case MEDIUM -> 3;
            case HIGH -> 4;
            case CRITICAL -> 5;
        };
    }

    private String formatMetricTitle(String metricName) {
        if (metricName == null || metricName.isBlank()) {
            return "Reliability anomaly detected";
        }
        return metricName.replace('_', ' ') + " anomaly detected";
    }

    private IncidentResponse mapToResponse(IncidentEntity entity) {
        return new IncidentResponse(
                entity.getId(),
                entity.getResourceId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getSeverity(),
                entity.getStatus(),
                entity.getConfidence(),
                entity.getRootCause(),
                entity.getDetectedAt(),
                entity.getResolvedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
