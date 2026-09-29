package com.aurora.platform.recovery.infrastructure.outbox;

import com.aurora.platform.recovery.application.orchestration.ControlledExecutionOrchestrator;
import com.aurora.platform.recovery.entity.OutboxEventStatus;
import com.aurora.platform.recovery.entity.RecoveryOutboxEventEntity;
import com.aurora.platform.recovery.repository.RecoveryOutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Scheduled polling dispatcher implementing the Transactional Outbox pattern.
 * Uses pessimistic row locking to prevent race conditions across concurrent workers.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_RETRIES = 3;
    private static final int BATCH_SIZE = 10;
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    private final RecoveryOutboxEventRepository outboxEventRepository;
    private final ControlledExecutionOrchestrator executionOrchestrator;
    private Clock clock = Clock.systemUTC();
    private volatile boolean enabled = true;

    @Autowired
    public OutboxDispatcher(
            RecoveryOutboxEventRepository outboxEventRepository,
            ControlledExecutionOrchestrator executionOrchestrator) {
        this.outboxEventRepository = outboxEventRepository;
        this.executionOrchestrator = executionOrchestrator;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${aurora.recovery.outbox.poll-interval-ms:5000}")
    public void scheduledPoll() {
        if (!enabled) {
            return;
        }
        try {
            dispatchPendingEvents();
        } catch (Exception ex) {
            log.error("Error during scheduled outbox polling", ex);
        }
    }

    /**
     * Polls and dispatches pending outbox events. Returns the count of dispatched events.
     */
    public int dispatchPendingEvents() {
        // Recover any events stuck in PROCESSING due to a worker crash
        recoverStuckProcessingEvents();

        List<RecoveryOutboxEventEntity> batch = claimPendingEvents();
        if (batch.isEmpty()) {
            return 0;
        }

        log.info("Claimed {} pending outbox events for dispatch", batch.size());
        int dispatched = 0;

        for (RecoveryOutboxEventEntity event : batch) {
            try {
                processSingleEvent(event);
                dispatched++;
            } catch (Exception ex) {
                log.error("Failed to process outbox event {}", event.getId(), ex);
                handleEventFailure(event.getId(), ex.getMessage());
            }
        }

        return dispatched;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<RecoveryOutboxEventEntity> claimPendingEvents() {
        List<RecoveryOutboxEventEntity> events = outboxEventRepository.findPendingForUpdate(
                OutboxEventStatus.PENDING, PageRequest.of(0, BATCH_SIZE));

        for (RecoveryOutboxEventEntity event : events) {
            event.setStatus(OutboxEventStatus.PROCESSING);
            outboxEventRepository.save(event);
        }
        return events;
    }

    public void processSingleEvent(RecoveryOutboxEventEntity event) {
        log.info("Dispatching outbox event {} for aggregate {}", event.getId(), event.getAggregateId());

        // Dispatch through the ControlledExecutionOrchestrator (which evaluates ExecutionAuthorizationGate)
        executionOrchestrator.executeAction(event.getAggregateId(), event.getIdempotencyKey());

        markEventCompleted(event.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventCompleted(java.util.UUID eventId) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            event.setStatus(OutboxEventStatus.SENT);
            event.setProcessedAt(clock.instant());
            outboxEventRepository.save(event);
            log.info("Outbox event {} marked as SENT", eventId);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleEventFailure(java.util.UUID eventId, String errorMessage) {
        outboxEventRepository.findById(eventId).ifPresent(event -> {
            int newRetry = event.getRetryCount() + 1;
            event.setRetryCount(newRetry);
            event.setLastError(errorMessage);

            if (newRetry >= MAX_RETRIES) {
                event.setStatus(OutboxEventStatus.DEAD_LETTER);
                log.warn("Outbox event {} reached max retries ({}), moved to DEAD_LETTER", eventId, MAX_RETRIES);
            } else {
                event.setStatus(OutboxEventStatus.PENDING);
                log.info("Outbox event {} reset to PENDING for retry {}/{}", eventId, newRetry, MAX_RETRIES);
            }
            outboxEventRepository.save(event);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverStuckProcessingEvents() {
        List<RecoveryOutboxEventEntity> processingEvents = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PROCESSING);

        Instant threshold = clock.instant().minus(PROCESSING_TIMEOUT);
        for (RecoveryOutboxEventEntity event : processingEvents) {
            if (event.getCreatedAt().isBefore(threshold)) {
                log.warn("Recovering stuck outbox event {} from PROCESSING to PENDING", event.getId());
                event.setStatus(OutboxEventStatus.PENDING);
                outboxEventRepository.save(event);
            }
        }
    }
}
