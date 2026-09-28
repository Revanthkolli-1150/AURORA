package com.aurora.platform.intelligence.anomaly.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.common.exception.ValidationException;
import com.aurora.platform.intelligence.anomaly.config.AnomalyDetectionProperties;
import com.aurora.platform.intelligence.anomaly.detector.AnomalyDetector;
import com.aurora.platform.intelligence.anomaly.detector.MadAnomalyDetector;
import com.aurora.platform.intelligence.anomaly.detector.ZScoreAnomalyDetector;
import com.aurora.platform.intelligence.anomaly.dto.AnomalyDetectionResponse;
import com.aurora.platform.intelligence.anomaly.model.AnomalyDetectorType;
import com.aurora.platform.intelligence.anomaly.model.AnomalyStatus;
import com.aurora.platform.resource.service.ResourceService;
import com.aurora.platform.telemetry.entity.TelemetryEventEntity;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.repository.TelemetryEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectionServiceTest {

    @Mock
    private ResourceService resourceService;

    @Mock
    private TelemetryEventRepository telemetryEventRepository;

    private ZScoreAnomalyDetector zScoreAnomalyDetector;
    private MadAnomalyDetector madAnomalyDetector;
    private AnomalyDetectionProperties properties;
    private AnomalyDetectionService service;
    private UUID resourceId;

    @BeforeEach
    void setUp() {
        zScoreAnomalyDetector = new ZScoreAnomalyDetector();
        madAnomalyDetector = new MadAnomalyDetector();
        properties = new AnomalyDetectionProperties();
        properties.setDefaultThreshold(3.0);
        properties.setMinSampleCount(10);

        service = new AnomalyDetectionServiceImpl(
                resourceService,
                telemetryEventRepository,
                List.of(zScoreAnomalyDetector, madAnomalyDetector),
                properties,
                null
        );
        resourceId = UUID.randomUUID();
    }

    private List<TelemetryEventEntity> generateHistoricalEvents(UUID resourceId, String metricName, List<Double> values) {
        List<TelemetryEventEntity> events = new ArrayList<>();
        Instant start = Instant.now().minus(values.size(), ChronoUnit.MINUTES);
        for (int i = 0; i < values.size(); i++) {
            events.add(TelemetryEventEntity.builder()
                    .id(UUID.randomUUID())
                    .resourceId(resourceId)
                    .timestamp(start.plus(i, ChronoUnit.MINUTES))
                    .type(TelemetryType.METRIC)
                    .metricName(metricName)
                    .value(values.get(i))
                    .unit("percent")
                    .build());
        }
        return events;
    }

    @Test
    @DisplayName("Should evaluate explicit observation value against all historical telemetry events")
    void shouldEvaluateExplicitValueAgainstHistoricalEvents() {
        List<Double> baselineValues = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0, 44.0, 43.0, 45.0, 44.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "cpu_usage", baselineValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "cpu_usage"))
                .thenReturn(events);

        AnomalyDetectionResponse response = service.evaluateAnomaly(resourceId, "cpu_usage", 95.0, null);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.currentValue()).isEqualTo(95.0);
        assertThat(response.sampleCount()).isEqualTo(12);
        assertThat(response.detectionMethod()).isEqualTo("Z_SCORE");
        assertThat(response.threshold()).isEqualTo(3.0);
        assertThat(response.anomalyScore()).isLessThan(1.0);
        assertThat(response.anomalyScore()).isGreaterThan(0.99);
        assertThat(response.anomalyScore()).isCloseTo(1.0 - Math.exp(-response.zScore() / 3.0), within(1e-6));
    }

    @Test
    @DisplayName("Should evaluate latest recorded event and exclude it from the historical baseline when value is omitted")
    void shouldEvaluateLatestRecordedEventExcludingFromBaseline() {
        // 13 events total: 12 normal baseline + 1 latest extreme spike (99.0)
        List<Double> allValues = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0, 44.0, 43.0, 45.0, 44.0, 99.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "cpu_usage", allValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "cpu_usage"))
                .thenReturn(events);

        AnomalyDetectionResponse response = service.evaluateAnomaly(resourceId, "cpu_usage", null, null);

        assertThat(response).isNotNull();
        assertThat(response.currentValue()).isEqualTo(99.0);
        assertThat(response.sampleCount()).isEqualTo(12); // Excluded latest event from baseline
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("12. Unknown resource should throw ResourceNotFoundException")
    void shouldThrowNotFoundWhenResourceDoesNotExist() {
        UUID unknownId = UUID.randomUUID();
        when(resourceService.existsById(unknownId)).thenReturn(false);

        assertThatThrownBy(() -> service.evaluateAnomaly(unknownId, "cpu_usage", 50.0, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Resource with ID '" + unknownId + "' not found");
    }

    @Test
    @DisplayName("13. Unknown metric without explicit value should throw ResourceNotFoundException")
    void shouldThrowNotFoundWhenNoTelemetryForMetricAndValueOmitted() {
        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "non_existent"))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.evaluateAnomaly(resourceId, "non_existent", null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No telemetry events found for metric 'non_existent'");
    }

    @Test
    @DisplayName("Should apply custom threshold override when specified")
    void shouldApplyCustomThresholdOverride() {
        List<Double> baselineValues = List.of(50.0, 52.0, 48.0, 51.0, 49.0, 50.0, 52.0, 48.0, 51.0, 49.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "mem", baselineValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "mem"))
                .thenReturn(events);

        // Lower threshold to 1.0 (more sensitive)
        AnomalyDetectionResponse response = service.evaluateAnomaly(resourceId, "mem", 53.5, 1.0);

        assertThat(response.threshold()).isEqualTo(1.0);
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("15. Validation: blank metricName should throw ValidationException")
    void shouldThrowValidationExceptionWhenMetricNameBlank() {
        assertThatThrownBy(() -> service.evaluateAnomaly(resourceId, "   ", 50.0, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Metric name is required");
    }

    @Test
    @DisplayName("15. Validation: non-positive threshold should throw ValidationException")
    void shouldThrowValidationExceptionWhenThresholdNonPositive() {
        assertThatThrownBy(() -> service.evaluateAnomaly(resourceId, "cpu_usage", 50.0, -1.0))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Anomaly threshold must be greater than zero");
    }

    @Test
    @DisplayName("Should invoke IncidentCorrelationService when anomaly status is ANOMALOUS")
    void shouldTriggerIncidentCorrelationWhenAnomalous() {
        com.aurora.platform.incident.service.IncidentCorrelationService correlationServiceMock =
                org.mockito.Mockito.mock(com.aurora.platform.incident.service.IncidentCorrelationService.class);

        AnomalyDetectionService serviceWithCorrelation = new AnomalyDetectionServiceImpl(
                resourceService,
                telemetryEventRepository,
                zScoreAnomalyDetector,
                properties,
                correlationServiceMock
        );

        List<Double> baselineValues = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0, 44.0, 43.0, 45.0, 44.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "cpu_usage", baselineValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "cpu_usage"))
                .thenReturn(events);

        AnomalyDetectionResponse response = serviceWithCorrelation.evaluateAnomaly(resourceId, "cpu_usage", 99.0, null);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        org.mockito.Mockito.verify(correlationServiceMock).correlateAnomaly(response);
    }

    @Test
    @DisplayName("13. Default detector should be Z_SCORE")
    void shouldDefaultToZScoreDetector() {
        AnomalyDetectionProperties props = new AnomalyDetectionProperties();
        assertThat(props.getDetector()).isEqualTo("Z_SCORE");
        assertThat(props.getDetectorType()).isEqualTo(AnomalyDetectorType.Z_SCORE);
        assertThat(((AnomalyDetectionServiceImpl) service).resolveDetector()).isInstanceOf(ZScoreAnomalyDetector.class);
    }

    @Test
    @DisplayName("14. Configured MAD selects MadAnomalyDetector")
    void shouldSelectMadDetectorWhenConfigured() {
        properties.setDetector("MAD");
        assertThat(properties.getDetectorType()).isEqualTo(AnomalyDetectorType.MAD);
        assertThat(((AnomalyDetectionServiceImpl) service).resolveDetector()).isInstanceOf(MadAnomalyDetector.class);

        List<Double> baselineValues = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0, 44.0, 43.0, 45.0, 44.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "cpu_usage", baselineValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "cpu_usage"))
                .thenReturn(events);

        AnomalyDetectionResponse response = service.evaluateAnomaly(resourceId, "cpu_usage", 95.0, null);
        assertThat(response.detectionMethod()).isEqualTo("MAD");
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
    }

    @Test
    @DisplayName("15. Invalid detector configuration should be rejected")
    void shouldRejectInvalidDetectorConfiguration() {
        assertThatThrownBy(() -> properties.setDetector("UNKNOWN_DETECTOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid anomaly detector configured");
    }

    @Test
    @DisplayName("16. Existing Z_SCORE behavior remains unchanged when explicitly configured")
    void shouldMaintainExistingZScoreBehaviorWhenConfigured() {
        properties.setDetector("Z_SCORE");
        List<Double> baselineValues = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0, 44.0, 43.0, 45.0, 44.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "cpu_usage", baselineValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "cpu_usage"))
                .thenReturn(events);

        AnomalyDetectionResponse response = service.evaluateAnomaly(resourceId, "cpu_usage", 95.0, null);
        assertThat(response.detectionMethod()).isEqualTo("Z_SCORE");
        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.zScore()).isNotNull();
    }

    @Test
    @DisplayName("17. Phase 1D-facing anomaly response remains compatible when using MAD detector")
    void shouldTriggerIncidentCorrelationWithMadDetectorWhenAnomalous() {
        com.aurora.platform.incident.service.IncidentCorrelationService correlationServiceMock =
                org.mockito.Mockito.mock(com.aurora.platform.incident.service.IncidentCorrelationService.class);

        properties.setDetector("MAD");

        AnomalyDetectionService serviceWithCorrelation = new AnomalyDetectionServiceImpl(
                resourceService,
                telemetryEventRepository,
                List.of(zScoreAnomalyDetector, madAnomalyDetector),
                properties,
                correlationServiceMock
        );

        List<Double> baselineValues = List.of(42.0, 43.0, 44.0, 45.0, 44.0, 43.0, 46.0, 45.0, 44.0, 43.0, 45.0, 44.0);
        List<TelemetryEventEntity> events = generateHistoricalEvents(resourceId, "cpu_usage", baselineValues);

        when(resourceService.existsById(resourceId)).thenReturn(true);
        when(telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(resourceId, "cpu_usage"))
                .thenReturn(events);

        AnomalyDetectionResponse response = serviceWithCorrelation.evaluateAnomaly(resourceId, "cpu_usage", 99.0, null);

        assertThat(response.status()).isEqualTo(AnomalyStatus.ANOMALOUS);
        assertThat(response.detectionMethod()).isEqualTo("MAD");
        org.mockito.Mockito.verify(correlationServiceMock).correlateAnomaly(response);
    }
}
