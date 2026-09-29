package com.aurora.platform.recovery;

import com.aurora.platform.recovery.application.verification.VerificationServiceImpl;
import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.ExecutionAttemptStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.VerificationEntity;
import com.aurora.platform.recovery.entity.VerificationStatus;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.VerificationRepository;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.entity.TelemetryType;
import com.aurora.platform.telemetry.service.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Phase5AVerificationTest {

    @Mock
    private VerificationRepository verificationRepository;

    @Mock
    private RecoveryActionRepository recoveryActionRepository;

    @Mock
    private TelemetryService telemetryService;

    private VerificationServiceImpl verificationService;
    private Instant now;

    private UUID verificationId;
    private UUID targetResourceId;
    private RecoveryActionEntity action;
    private ExecutionAttemptEntity attempt;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-09-30T14:00:00Z");
        Clock clock = Clock.fixed(now, ZoneId.of("UTC"));

        verificationService = new VerificationServiceImpl(
                verificationRepository,
                recoveryActionRepository,
                telemetryService
        );
        verificationService.setClock(clock);

        verificationId = UUID.randomUUID();
        targetResourceId = UUID.randomUUID();

        action = RecoveryActionEntity.builder()
                .id(UUID.randomUUID())
                .actionType("FLUSH_CACHE")
                .targetResourceId(targetResourceId)
                .targetEnvironment("staging")
                .targetResourceName("redis-cache")
                .status(RecoveryActionStatus.IN_PROGRESS)
                .build();

        attempt = ExecutionAttemptEntity.builder()
                .id(UUID.randomUUID())
                .recoveryAction(action)
                .status(ExecutionAttemptStatus.SUCCEEDED)
                .completedAt(now.minusSeconds(60))
                .build();
    }

    private VerificationEntity createVerification(double baseline, double threshold) {
        return VerificationEntity.builder()
                .id(verificationId)
                .executionAttempt(attempt)
                .targetResourceId(targetResourceId)
                .metricName("cache.latency")
                .baselineValue(baseline)
                .policyThreshold(threshold)
                .status(VerificationStatus.SCHEDULED)
                .observationStartedAt(now.minusSeconds(60))
                .build();
    }

    @Test
    @DisplayName("S11 & S12: Healthy Verification - metric normalizes below threshold, action marked COMPLETED")
    void healthyVerificationTransitionsActionToCompleted() {
        VerificationEntity v = createVerification(150.0, 50.0);
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any(VerificationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TelemetryEventResponse sample1 = new TelemetryEventResponse(
                UUID.randomUUID(), targetResourceId, now.minusSeconds(30),
                TelemetryType.METRIC, "cache.latency", 42.0, "ms", Map.of()
        );
        TelemetryEventResponse sample2 = new TelemetryEventResponse(
                UUID.randomUUID(), targetResourceId, now.minusSeconds(10),
                TelemetryType.METRIC, "cache.latency", 38.0, "ms", Map.of()
        );
        when(telemetryService.getTelemetrySince(targetResourceId, "cache.latency", v.getObservationStartedAt()))
                .thenReturn(List.of(sample1, sample2));

        VerificationEntity result = verificationService.executeVerification(verificationId);

        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_HEALTHY);
        assertThat(result.getObservedSamplesCount()).isEqualTo(2);
        assertThat(result.getFinalObservedValue()).isEqualTo(38.0);
        assertThat(result.getVerificationNotes()).contains("stabilized below threshold");

        // Action transitions to terminal COMPLETED
        verify(recoveryActionRepository).save(action);
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.COMPLETED);
        assertThat(action.getResult()).contains("completed and verified healthy");
    }

    @Test
    @DisplayName("S11 & S12: Degraded Verification - metric worsens beyond baseline, action marked FAILED")
    void degradedVerificationTransitionsActionToFailed() {
        VerificationEntity v = createVerification(150.0, 50.0);
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any(VerificationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        TelemetryEventResponse sample = new TelemetryEventResponse(
                UUID.randomUUID(), targetResourceId, now.minusSeconds(10),
                TelemetryType.METRIC, "cache.latency", 220.0, "ms", Map.of()
        );
        when(telemetryService.getTelemetrySince(targetResourceId, "cache.latency", v.getObservationStartedAt()))
                .thenReturn(List.of(sample));

        VerificationEntity result = verificationService.executeVerification(verificationId);

        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_DEGRADED);
        assertThat(result.getFinalObservedValue()).isEqualTo(220.0);
        assertThat(result.getVerificationNotes()).contains("degraded beyond baseline");

        // Action transitions to terminal FAILED
        verify(recoveryActionRepository).save(action);
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
        assertThat(action.getResult()).contains("telemetry regressed to degraded state");
    }

    @Test
    @DisplayName("S12: Truthful Verification - Missing telemetry fails closed to VERIFIED_INCONCLUSIVE, never healthy")
    void missingTelemetryFailsClosedToInconclusive() {
        VerificationEntity v = createVerification(150.0, 50.0);
        when(verificationRepository.findById(verificationId)).thenReturn(Optional.of(v));
        when(verificationRepository.save(any(VerificationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Telemetry service returns zero telemetry events
        when(telemetryService.getTelemetrySince(targetResourceId, "cache.latency", v.getObservationStartedAt()))
                .thenReturn(Collections.emptyList());

        VerificationEntity result = verificationService.executeVerification(verificationId);

        // Invariant S12: Failure to obtain telemetry evidence CANNOT become VERIFIED_HEALTHY
        assertThat(result.getStatus()).isEqualTo(VerificationStatus.VERIFIED_INCONCLUSIVE);
        assertThat(result.getStatus()).isNotEqualTo(VerificationStatus.VERIFIED_HEALTHY);
        assertThat(result.getObservedSamplesCount()).isEqualTo(0);
        assertThat(result.getVerificationNotes()).contains("Truthful verification: No post-action telemetry observed");

        // Action transitions to terminal FAILED
        verify(recoveryActionRepository).save(action);
        assertThat(action.getStatus()).isEqualTo(RecoveryActionStatus.FAILED);
        assertThat(action.getResult()).contains("Verification inconclusive");
    }
}
