package com.aurora.platform.recovery.application.verification;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.ExecutionAttemptStatus;
import com.aurora.platform.recovery.entity.RecoveryActionEntity;
import com.aurora.platform.recovery.entity.RecoveryActionStatus;
import com.aurora.platform.recovery.entity.VerificationEntity;
import com.aurora.platform.recovery.entity.VerificationStatus;
import com.aurora.platform.recovery.repository.RecoveryActionRepository;
import com.aurora.platform.recovery.repository.VerificationRepository;
import com.aurora.platform.telemetry.dto.TelemetryEventResponse;
import com.aurora.platform.telemetry.service.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class VerificationServiceImpl implements VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationServiceImpl.class);

    private final VerificationRepository verificationRepository;
    private final RecoveryActionRepository recoveryActionRepository;
    private final TelemetryService telemetryService;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public VerificationServiceImpl(
            VerificationRepository verificationRepository,
            RecoveryActionRepository recoveryActionRepository,
            TelemetryService telemetryService) {
        this.verificationRepository = verificationRepository;
        this.recoveryActionRepository = recoveryActionRepository;
        this.telemetryService = telemetryService;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    @Transactional
    public VerificationEntity scheduleVerification(
            ExecutionAttemptEntity attempt,
            String metricName,
            double baselineValue,
            double policyThreshold) {

        if (attempt.getStatus() != ExecutionAttemptStatus.SUCCEEDED) {
            throw new IllegalStateException("Cannot schedule verification for non-succeeded attempt: " + attempt.getStatus());
        }

        RecoveryActionEntity action = attempt.getRecoveryAction();
        Instant now = clock.instant();

        VerificationEntity verification = VerificationEntity.builder()
                .executionAttempt(attempt)
                .targetResourceId(action.getTargetResourceId())
                .metricName(metricName != null ? metricName : "system.health")
                .baselineValue(baselineValue)
                .policyThreshold(policyThreshold)
                .status(VerificationStatus.SCHEDULED)
                .observationStartedAt(now)
                .observedSamplesCount(0)
                .build();

        return verificationRepository.save(verification);
    }

    @Override
    @Transactional
    public VerificationEntity executeVerification(UUID verificationId) {
        VerificationEntity verification = verificationRepository.findById(verificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Verification not found with ID: " + verificationId));

        Instant now = clock.instant();
        verification.setStatus(VerificationStatus.OBSERVING);

        List<TelemetryEventResponse> events = telemetryService.getTelemetrySince(
                verification.getTargetResourceId(),
                verification.getMetricName(),
                verification.getObservationStartedAt()
        );

        RecoveryActionEntity action = verification.getExecutionAttempt().getRecoveryAction();

        // S12: Truthful verification - if telemetry is missing/empty, CANNOT mark healthy
        if (events == null || events.isEmpty()) {
            log.warn("No telemetry observed for target {} metric {} since {}. Verification inconclusive.",
                    verification.getTargetResourceId(), verification.getMetricName(), verification.getObservationStartedAt());
            verification.setStatus(VerificationStatus.VERIFIED_INCONCLUSIVE);
            verification.setObservationEndedAt(now);
            verification.setObservedSamplesCount(0);
            verification.setVerificationNotes("Truthful verification: No post-action telemetry observed. Fails closed to INCONCLUSIVE.");

            action.setStatus(RecoveryActionStatus.FAILED);
            action.setCompletedAt(now);
            action.setResult("Verification inconclusive: no post-action telemetry observed");
            recoveryActionRepository.save(action);

            return verificationRepository.save(verification);
        }

        verification.setObservedSamplesCount(events.size());
        double finalValue = events.get(events.size() - 1).value();
        verification.setFinalObservedValue(finalValue);
        verification.setObservationEndedAt(now);

        // Evaluate metric convergence
        if (finalValue <= verification.getPolicyThreshold()) {
            verification.setStatus(VerificationStatus.VERIFIED_HEALTHY);
            verification.setVerificationNotes(String.format(
                    "Observed value %.2f stabilized below threshold %.2f (baseline: %.2f)",
                    finalValue, verification.getPolicyThreshold(), verification.getBaselineValue()));

            action.setStatus(RecoveryActionStatus.COMPLETED);
            action.setCompletedAt(now);
            action.setResult("Recovery action completed and verified healthy");
        } else if (finalValue > verification.getBaselineValue()) {
            verification.setStatus(VerificationStatus.VERIFIED_DEGRADED);
            verification.setVerificationNotes(String.format(
                    "Observed value %.2f degraded beyond baseline %.2f",
                    finalValue, verification.getBaselineValue()));

            action.setStatus(RecoveryActionStatus.FAILED);
            action.setCompletedAt(now);
            action.setResult("Recovery action failed: telemetry regressed to degraded state");
        } else {
            verification.setStatus(VerificationStatus.VERIFIED_INCONCLUSIVE);
            verification.setVerificationNotes(String.format(
                    "Observed value %.2f improved from baseline %.2f but remains above threshold %.2f",
                    finalValue, verification.getBaselineValue(), verification.getPolicyThreshold()));

            action.setStatus(RecoveryActionStatus.FAILED);
            action.setCompletedAt(now);
            action.setResult("Recovery action inconclusive: metric did not fully converge");
        }

        recoveryActionRepository.save(action);
        return verificationRepository.save(verification);
    }
}
