package com.aurora.platform.intelligence.historical;

import com.aurora.platform.incident.entity.IncidentSeverity;
import com.aurora.platform.intelligence.historical.application.port.out.HistoricalIncidentQueryPort;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.historical.dto.SimilarIncidentResponse;
import com.aurora.platform.intelligence.historical.service.HistoricalIncidentServiceImpl;
import com.aurora.platform.resource.entity.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalIncidentDeterminismTest {

    @Mock
    private HistoricalIncidentQueryPort queryPort;

    @Test
    @DisplayName("23.G: 100 repeated executions produce bit-for-bit identical scores, ordering, explanations, and matched collections")
    void shouldBeBitForBitDeterministicAcross100Runs() {
        HistoricalIncidentServiceImpl service = new HistoricalIncidentServiceImpl(queryPort);

        UUID targetId = UUID.randomUUID();
        IncidentSignature target = new IncidentSignature(
                targetId, UUID.randomUUID(), "order-service", ResourceType.SERVICE,
                IncidentSeverity.CRITICAL,
                Set.of("http_5xx_rate", "jvm_memory_used", "p99_latency"),
                Set.of("DOWNSTREAM:GATEWAY", "UPSTREAM:DATABASE", "UPSTREAM:CACHE"),
                "Target primary cause", Instant.now(), null
        );

        List<IncidentSignature> candidates = new ArrayList<>();
        Instant baseTime = Instant.parse("2026-09-20T10:00:00Z");

        for (int i = 0; i < 50; i++) {
            UUID cid = UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i));
            candidates.add(new IncidentSignature(
                    cid,
                    UUID.randomUUID(),
                    "service-" + (i % 5),
                    ResourceType.values()[i % ResourceType.values().length],
                    IncidentSeverity.values()[i % IncidentSeverity.values().length],
                    (i % 2 == 0)
                            ? Set.of("http_5xx_rate", "metric_" + i)
                            : Set.of("jvm_memory_used", "p99_latency", "other_" + i),
                    (i % 3 == 0)
                            ? Set.of("UPSTREAM:DATABASE", "DOWNSTREAM:GATEWAY")
                            : Set.of("UPSTREAM:CACHE"),
                    "Candidate Root Cause " + i,
                    baseTime.minusSeconds(2000 - i * 10),
                    baseTime.minusSeconds(1000 - i * 10)
            ));
        }

        when(queryPort.findSignature(targetId)).thenReturn(Optional.of(target));
        when(queryPort.findCandidateCorpusSignatures(eq(targetId), anyInt())).thenReturn(candidates);

        // Run baseline (run 0)
        List<SimilarIncidentResponse> baseline = service.findSimilarIncidents(targetId, 15, 0.20);
        assertThat(baseline).isNotEmpty();

        // Run 100 iterations and assert complete identity with baseline
        for (int run = 1; run <= 100; run++) {
            List<SimilarIncidentResponse> repeated = service.findSimilarIncidents(targetId, 15, 0.20);

            assertThat(repeated.size())
                    .as("Run %d result size matches baseline", run)
                    .isEqualTo(baseline.size());

            for (int j = 0; j < baseline.size(); j++) {
                SimilarIncidentResponse b = baseline.get(j);
                SimilarIncidentResponse r = repeated.get(j);

                assertThat(r.historicalIncidentId())
                        .as("Run %d item %d historicalIncidentId", run, j)
                        .isEqualTo(b.historicalIncidentId());

                assertThat(r.similarityScore())
                        .as("Run %d item %d similarityScore", run, j)
                        .isEqualTo(b.similarityScore());

                assertThat(r.explanation())
                        .as("Run %d item %d explanation", run, j)
                        .isEqualTo(b.explanation());

                assertThat(r.breakdown().metricScore())
                        .as("Run %d item %d metricScore", run, j)
                        .isEqualTo(b.breakdown().metricScore());

                assertThat(r.breakdown().topologyScore())
                        .as("Run %d item %d topologyScore", run, j)
                        .isEqualTo(b.breakdown().topologyScore());

                assertThat(r.breakdown().resourceScore())
                        .as("Run %d item %d resourceScore", run, j)
                        .isEqualTo(b.breakdown().resourceScore());

                assertThat(r.breakdown().severityScore())
                        .as("Run %d item %d severityScore", run, j)
                        .isEqualTo(b.breakdown().severityScore());

                assertThat(r.breakdown().matchedMetrics())
                        .as("Run %d item %d matchedMetrics", run, j)
                        .containsExactlyElementsOf(b.breakdown().matchedMetrics());

                assertThat(r.breakdown().matchedTopologyTokens())
                        .as("Run %d item %d matchedTopologyTokens", run, j)
                        .containsExactlyElementsOf(b.breakdown().matchedTopologyTokens());
            }
        }
    }
}
