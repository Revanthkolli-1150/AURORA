package com.aurora.platform.incident.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.dto.CreateIncidentRequest;
import com.aurora.platform.incident.dto.IncidentResponse;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.resource.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentAnomalyEvidenceRepository evidenceRepository;

    @Mock
    private ResourceService resourceService;

    private IncidentService incidentService;

    @BeforeEach
    void setUp() {
        incidentService = new IncidentServiceImpl(incidentRepository, evidenceRepository, resourceService);
    }

    @Test
    @DisplayName("Should create incident successfully when target resource exists")
    void shouldCreateIncidentSuccessfully() {
        UUID resourceId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();

        CreateIncidentRequest request = new CreateIncidentRequest(
                resourceId,
                "Cascading connection pool exhaustion",
                "Connections saturated due to thread pool starvation",
                IncidentSeverity.CRITICAL,
                IncidentStatus.DETECTED,
                0.94,
                "Database max connection limit exceeded",
                Instant.now()
        );

        when(resourceService.existsById(resourceId)).thenReturn(true);

        IncidentEntity entity = IncidentEntity.builder()
                .id(incidentId)
                .resourceId(resourceId)
                .title(request.title())
                .description(request.description())
                .severity(request.severity())
                .status(request.status())
                .confidence(request.confidence())
                .rootCause(request.rootCause())
                .detectedAt(request.detectedAt())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(incidentRepository.save(any(IncidentEntity.class))).thenReturn(entity);

        IncidentResponse response = incidentService.createIncident(request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(incidentId);
        assertThat(response.severity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(response.status()).isEqualTo(IncidentStatus.DETECTED);
        assertThat(response.confidence()).isEqualTo(0.94);

        verify(incidentRepository).save(any(IncidentEntity.class));
    }

    @Test
    @DisplayName("Should get incident by ID")
    void shouldGetIncidentById() {
        UUID incidentId = UUID.randomUUID();
        IncidentEntity entity = IncidentEntity.builder()
                .id(incidentId)
                .resourceId(UUID.randomUUID())
                .title("High memory pressure")
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.INVESTIGATING)
                .confidence(0.85)
                .detectedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(entity));

        IncidentResponse response = incidentService.getIncidentById(incidentId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(incidentId);
        assertThat(response.title()).isEqualTo("High memory pressure");
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when incident ID does not exist")
    void shouldThrowNotFoundWhenIncidentMissing() {
        UUID missingId = UUID.randomUUID();
        when(incidentRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.getIncidentById(missingId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incident with ID '" + missingId + "' not found");
    }

    @Test
    @DisplayName("Should list incidents filtered by status")
    void shouldListIncidentsByStatus() {
        IncidentEntity entity = IncidentEntity.builder()
                .id(UUID.randomUUID())
                .resourceId(UUID.randomUUID())
                .title("Service degradation")
                .severity(IncidentSeverity.MEDIUM)
                .status(IncidentStatus.DETECTED)
                .confidence(0.78)
                .detectedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(incidentRepository.findByStatus(IncidentStatus.DETECTED)).thenReturn(List.of(entity));

        List<IncidentResponse> results = incidentService.getAllIncidents(null, IncidentStatus.DETECTED, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(IncidentStatus.DETECTED);
    }

    @Test
    @DisplayName("Should return evidence list when incident exists")
    void shouldReturnEvidenceWhenIncidentExists() {
        UUID incidentId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity evidenceEntity =
                com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity.builder()
                        .id(UUID.randomUUID())
                        .incidentId(incidentId)
                        .resourceId(resourceId)
                        .metricName("cpu_usage")
                        .observedValue(95.0)
                        .anomalyScore(1.0)
                        .zScore(4.2)
                        .detectionMethod("Z_SCORE")
                        .observedAt(Instant.now())
                        .createdAt(Instant.now())
                        .build();

        when(incidentRepository.existsById(incidentId)).thenReturn(true);
        when(evidenceRepository.findByIncidentIdOrderByObservedAtAsc(incidentId)).thenReturn(List.of(evidenceEntity));

        List<com.aurora.platform.incident.dto.IncidentAnomalyEvidenceResponse> evidence =
                incidentService.getEvidenceByIncidentId(incidentId);

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).metricName()).isEqualTo("cpu_usage");
        assertThat(evidence.get(0).observedValue()).isEqualTo(95.0);
    }

    @Test
    @DisplayName("Should throw ResourceNotFoundException when retrieving evidence for non-existent incident")
    void shouldThrowNotFoundWhenRetrievingEvidenceForMissingIncident() {
        UUID missingId = UUID.randomUUID();
        when(incidentRepository.existsById(missingId)).thenReturn(false);

        assertThatThrownBy(() -> incidentService.getEvidenceByIncidentId(missingId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incident with ID '" + missingId + "' not found");
    }
}
