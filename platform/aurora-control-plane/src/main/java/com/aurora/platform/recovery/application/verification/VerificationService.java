package com.aurora.platform.recovery.application.verification;

import com.aurora.platform.recovery.entity.ExecutionAttemptEntity;
import com.aurora.platform.recovery.entity.VerificationEntity;

import java.util.UUID;

public interface VerificationService {

    VerificationEntity scheduleVerification(ExecutionAttemptEntity attempt, String metricName, double baselineValue, double policyThreshold);

    VerificationEntity executeVerification(UUID verificationId);
}
