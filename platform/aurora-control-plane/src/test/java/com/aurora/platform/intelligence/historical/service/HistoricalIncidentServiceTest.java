package com.aurora.platform.intelligence.historical.service;

import com.aurora.platform.common.exception.ResourceNotFoundException;
import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.intelligence.historical.application.port.out.HistoricalIncidentQueryPort;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalIncidentServiceTest {

    @Mock
    private HistoricalIncidentQueryPort queryPort;

    private HistoricalIncidentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HistoricalIncidentServiceImpl(queryPort);
    }

    @Test
    @DisplayName("1. Throws ResourceNotFoundException when target incident does not exist")
    void shouldThrowWhenIncidentNotFound() {
        UUID nonExistent = UUID.randomUUID();
        when(queryPort.findSignature(nonExistent)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findSimilarIncidents(nonExistent, 5, 0.30))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incident with ID '" + nonExistent + "' not found");
    }

    @Test
    @DisplayName("6. Candidate window capped at 200 via query port")
    void shouldCapCandidateWindowAt200() {
        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu"), Set.of(),
                null, Instant.now(), null
        );

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt())).thenReturn(List.of());

        service.findSimilarIncidents(targetId, 5, 0.30);

        ArgumentCaptor<Integer> capCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(queryPort).findCandidateCorpusSignatures(eq(targetId), capCaptor.capture());
        assertThat(capCaptor.getValue()).isEqualTo(200);
    }

    @Test
    @DisplayName("7. minScore boundary is inclusive (score == minScore is included)")
    void shouldIncludeExactMinScoreBoundary() {
        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu"), Set.of(),
                null, Instant.now(), null
        );

        // Candidate produces exact 0.30 score: resource match (1.0 * 0.20 = 0.20) + severity match (1.0 * 0.10 = 0.10)
        IncidentSignature candExact = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "srv-other", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("other_metric"), Set.of("other_topo"),
                null, Instant.now().minusSeconds(100), Instant.now()
        );

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt())).thenReturn(List.of(candExact));

        List<SimilarIncidentResponse> results = service.findSimilarIncidents(targetId, 5, 0.30);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).similarityScore()).isEqualTo(0.30);
    }

    @Test
    @DisplayName("8. Default limit is 5 and default minScore is 0.30")
    void shouldApplyDefaultParameters() {
        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu"), Set.of(),
                null, Instant.now(), null
        );

        List<IncidentSignature> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(new IncidentSignature(
                    UUID.randomUUID(), UUID.randomUUID(), "srv-" + i, ResourceType.SERVICE,
                    IncidentSeverity.HIGH, Set.of("cpu"), Set.of(),
                    null, Instant.now().minusSeconds(i * 10), Instant.now()
            ));
        }

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt())).thenReturn(candidates);

        // Call with null params -> should use default limit=5, minScore=0.30
        List<SimilarIncidentResponse> results = service.findSimilarIncidents(targetId, null, null);
        assertThat(results).hasSize(5);
    }

    @Test
    @DisplayName("9, 10. Limit maximum is 20, and invalid limits (< 1 or > 20) are rejected")
    void shouldValidateLimit() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.findSimilarIncidents(id, 0, 0.30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 20");

        assertThatThrownBy(() -> service.findSimilarIncidents(id, 21, 0.30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be between 1 and 20");
    }

    @Test
    @DisplayName("11. Invalid minScore rejected (< 0.0 or > 1.0 or NaN)")
    void shouldValidateMinScore() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.findSimilarIncidents(id, 5, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore must be between 0.00 and 1.00");

        assertThatThrownBy(() -> service.findSimilarIncidents(id, 5, 1.05))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore must be between 0.00 and 1.00");

        assertThatThrownBy(() -> service.findSimilarIncidents(id, 5, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore must be between 0.00 and 1.00");
    }

    @Test
    @DisplayName("12, 13, 14. Deterministic ranking: score DESC, resolvedAt DESC, incidentId ASC")
    void shouldRankDeterministicallyWithAllTieBreakers() {
        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu"), Set.of(),
                null, Instant.now(), null
        );

        Instant resolvedTime = Instant.now().minusSeconds(100);
        UUID uuidSmall = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID uuidLarge = UUID.fromString("00000000-0000-0000-0000-000000000002");

        // High score candidate (1.00)
        IncidentSignature candHighScore = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "srv-high", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu"), Set.of(),
                null, Instant.now().minusSeconds(500), Instant.now().minusSeconds(400)
        );

        // Tied score candidates, but with different resolvedAt
        IncidentSignature candRecentResolved = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "srv-recent", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("other"), Set.of(),
                null, Instant.now().minusSeconds(300), resolvedTime.plusSeconds(50)
        );

        // Tied score candidates with identical resolvedAt: tie broken by incidentId ASC
        IncidentSignature candIdSmall = new IncidentSignature(
                uuidSmall, UUID.randomUUID(), "srv-tied-1", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("other"), Set.of(),
                null, Instant.now().minusSeconds(300), resolvedTime
        );
        IncidentSignature candIdLarge = new IncidentSignature(
                uuidLarge, UUID.randomUUID(), "srv-tied-2", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("other"), Set.of(),
                null, Instant.now().minusSeconds(300), resolvedTime
        );

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt()))
                .thenReturn(List.of(candIdLarge, candRecentResolved, candIdSmall, candHighScore));

        List<SimilarIncidentResponse> results = service.findSimilarIncidents(targetId, 5, 0.30);

        assertThat(results).hasSize(4);
        // 1. Highest score (1.00)
        assertThat(results.get(0).historicalIncidentId()).isEqualTo(candHighScore.incidentId());
        // 2. Tied score (0.30), but newer resolvedAt
        assertThat(results.get(1).historicalIncidentId()).isEqualTo(candRecentResolved.incidentId());
        // 3. Tied score & identical resolvedAt: uuidSmall < uuidLarge
        assertThat(results.get(2).historicalIncidentId()).isEqualTo(uuidSmall);
        // 4. Tied score & identical resolvedAt: uuidLarge
        assertThat(results.get(3).historicalIncidentId()).isEqualTo(uuidLarge);
    }

    @Test
    @DisplayName("15. Empty result when no candidate meets threshold")
    void shouldReturnEmptyWhenNoCandidateMeetsThreshold() {
        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu"), Set.of("UPSTREAM:DATABASE"),
                null, Instant.now(), null
        );

        IncidentSignature lowScoreCandidate = new IncidentSignature(
                UUID.randomUUID(), UUID.randomUUID(), "db", ResourceType.DATABASE,
                IncidentSeverity.LOW, Set.of("disk"), Set.of("UPSTREAM:CACHE"),
                null, Instant.now().minusSeconds(200), Instant.now()
        );

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt())).thenReturn(List.of(lowScoreCandidate));

        List<SimilarIncidentResponse> results = service.findSimilarIncidents(targetId, 5, 0.30);
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("16. Deterministic repeated result ordering across 100 executions")
    void shouldProduceIdenticalOrderingAcrossRepeatedExecutions() {
        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "srv", ResourceType.SERVICE,
                IncidentSeverity.HIGH, Set.of("cpu", "mem"), Set.of("UPSTREAM:DB"),
                null, Instant.now(), null
        );

        List<IncidentSignature> candidates = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candidates.add(new IncidentSignature(
                    UUID.randomUUID(), UUID.randomUUID(), "srv-" + i,
                    (i % 2 == 0) ? ResourceType.SERVICE : ResourceType.SERVER,
                    (i % 3 == 0) ? IncidentSeverity.HIGH : IncidentSeverity.MEDIUM,
                    Set.of("cpu", "other_" + i),
                    Set.of("UPSTREAM:DB"),
                    "RCA-" + i,
                    Instant.now().minusSeconds(1000 - i * 10),
                    Instant.now().minusSeconds(500 - i * 10)
            ));
        }

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt())).thenReturn(candidates);

        List<SimilarIncidentResponse> initial = service.findSimilarIncidents(targetId, 10, 0.30);
        List<UUID> initialOrder = initial.stream().map(SimilarIncidentResponse::historicalIncidentId).toList();

        for (int run = 0; run < 100; run++) {
            List<SimilarIncidentResponse> repeated = service.findSimilarIncidents(targetId, 10, 0.30);
            List<UUID> repeatedOrder = repeated.stream().map(SimilarIncidentResponse::historicalIncidentId).toList();
            assertThat(repeatedOrder).isEqualTo(initialOrder);
        }
    }
}
