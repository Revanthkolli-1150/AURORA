package com.aurora.platform.intelligence.historical.infrastructure.adapter;

import com.aurora.platform.dependency.entity.ResourceDependencyEntity;
import com.aurora.platform.dependency.repository.ResourceDependencyRepository;
import com.aurora.platform.incident.entity.IncidentAnomalyEvidenceEntity;
import com.aurora.platform.incident.entity.IncidentEntity;
import com.aurora.platform.incident.entity.IncidentStatus;
import com.aurora.platform.incident.repository.IncidentAnomalyEvidenceRepository;
import com.aurora.platform.incident.repository.IncidentRepository;
import com.aurora.platform.intelligence.historical.application.port.out.HistoricalIncidentQueryPort;
import com.aurora.platform.intelligence.historical.domain.model.IncidentSignature;
import com.aurora.platform.intelligence.rca.entity.RcaAnalysisEntity;
import com.aurora.platform.intelligence.rca.entity.RcaCandidateEntity;
import com.aurora.platform.intelligence.rca.repository.RcaAnalysisRepository;
import com.aurora.platform.intelligence.rca.repository.RcaCandidateRepository;
import com.aurora.platform.resource.entity.ResourceEntity;
import com.aurora.platform.resource.entity.ResourceType;
import com.aurora.platform.resource.repository.ResourceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Infrastructure query adapter implementing {@link HistoricalIncidentQueryPort}.
 * Queries historical incident entities, anomaly evidence, resource topologies, and RCA diagnostics
 * at query-time without modifying database schemas or introducing persistent cache tables.
 *
 * <p>Batch queries are used to eliminate N+1 query behavior across the bounded historical corpus.
 */
@Component
@Transactional(readOnly = true)
public class HistoricalIncidentQueryAdapter implements HistoricalIncidentQueryPort {

    private final IncidentRepository incidentRepository;
    private final IncidentAnomalyEvidenceRepository evidenceRepository;
    private final ResourceRepository resourceRepository;
    private final ResourceDependencyRepository dependencyRepository;
    private final RcaAnalysisRepository rcaAnalysisRepository;
    private final RcaCandidateRepository rcaCandidateRepository;

    public HistoricalIncidentQueryAdapter(
            IncidentRepository incidentRepository,
            IncidentAnomalyEvidenceRepository evidenceRepository,
            ResourceRepository resourceRepository,
            ResourceDependencyRepository dependencyRepository,
            RcaAnalysisRepository rcaAnalysisRepository,
            RcaCandidateRepository rcaCandidateRepository
    ) {
        this.incidentRepository = incidentRepository;
        this.evidenceRepository = evidenceRepository;
        this.resourceRepository = resourceRepository;
        this.dependencyRepository = dependencyRepository;
        this.rcaAnalysisRepository = rcaAnalysisRepository;
        this.rcaCandidateRepository = rcaCandidateRepository;
    }

    @Override
    public Optional<IncidentSignature> findSignature(UUID incidentId) {
        return incidentRepository.findById(incidentId)
                .flatMap(incident -> buildSignaturesBatch(List.of(incident)).stream().findFirst());
    }

    @Override
    public List<IncidentSignature> findCandidateCorpusSignatures(UUID excludedIncidentId, int maxCandidates) {
        int limit = Math.max(1, Math.min(200, maxCandidates));
        List<IncidentEntity> candidates = incidentRepository.findByStatusAndIdNotOrderByResolvedAtDescIdAsc(
                IncidentStatus.RESOLVED,
                excludedIncidentId,
                PageRequest.of(0, limit)
        );

        return buildSignaturesBatch(candidates);
    }

    /**
     * Batch-constructs canonical {@link IncidentSignature} instances for a list of incidents
     * using batch relational lookups to prevent N+1 queries.
     */
    private List<IncidentSignature> buildSignaturesBatch(List<IncidentEntity> incidents) {
        if (incidents == null || incidents.isEmpty()) {
            return Collections.emptyList();
        }

        List<UUID> incidentIds = incidents.stream().map(IncidentEntity::getId).toList();
        Set<UUID> directResourceIds = incidents.stream().map(IncidentEntity::getResourceId).collect(Collectors.toSet());

        // 1. Batch-fetch anomaly evidence for all incidents
        Map<UUID, Set<String>> evidenceByIncidentId = new HashMap<>();
        List<IncidentAnomalyEvidenceEntity> evidenceList = evidenceRepository.findByIncidentIdIn(incidentIds);
        for (IncidentAnomalyEvidenceEntity ev : evidenceList) {
            if (ev.getMetricName() != null && !ev.getMetricName().isBlank()) {
                evidenceByIncidentId.computeIfAbsent(ev.getIncidentId(), k -> new TreeSet<>())
                        .add(ev.getMetricName());
            }
        }

        // 2. Batch-fetch 1-hop topology dependencies
        List<ResourceDependencyEntity> outgoingList = dependencyRepository.findBySourceResourceIdIn(directResourceIds);
        List<ResourceDependencyEntity> incomingList = dependencyRepository.findByTargetResourceIdIn(directResourceIds);

        // 3. Collect all needed resource IDs (incident resources + 1-hop neighbor resources)
        Set<UUID> allNeededResourceIds = new HashSet<>(directResourceIds);
        for (ResourceDependencyEntity dep : outgoingList) {
            allNeededResourceIds.add(dep.getTargetResourceId());
        }
        for (ResourceDependencyEntity dep : incomingList) {
            allNeededResourceIds.add(dep.getSourceResourceId());
        }

        // 4. Batch-fetch all relevant resource entities
        List<ResourceEntity> resources = resourceRepository.findAllById(allNeededResourceIds);
        Map<UUID, ResourceEntity> resourceMap = resources.stream()
                .collect(Collectors.toMap(ResourceEntity::getId, r -> r, (a, b) -> a));

        // 5. Build canonical topology tokens per resource
        Map<UUID, Set<String>> topologyByResourceId = new HashMap<>();
        for (ResourceDependencyEntity dep : outgoingList) {
            ResourceEntity target = resourceMap.get(dep.getTargetResourceId());
            if (target != null) {
                topologyByResourceId.computeIfAbsent(dep.getSourceResourceId(), k -> new TreeSet<>())
                        .add("UPSTREAM:" + target.getType().name());
            }
        }
        for (ResourceDependencyEntity dep : incomingList) {
            ResourceEntity source = resourceMap.get(dep.getSourceResourceId());
            if (source != null) {
                topologyByResourceId.computeIfAbsent(dep.getTargetResourceId(), k -> new TreeSet<>())
                        .add("DOWNSTREAM:" + source.getType().name());
            }
        }

        // 6. Batch-fetch RCA diagnostics
        Map<UUID, RcaAnalysisEntity> latestRcaByIncident = new HashMap<>();
        List<RcaAnalysisEntity> analyses = rcaAnalysisRepository.findByIncidentIdInOrderByCreatedAtDesc(incidentIds);
        for (RcaAnalysisEntity analysis : analyses) {
            latestRcaByIncident.putIfAbsent(analysis.getIncidentId(), analysis);
        }

        Map<UUID, String> primaryRcaCauseByIncident = new HashMap<>();
        if (!latestRcaByIncident.isEmpty()) {
            Set<UUID> analysisIds = latestRcaByIncident.values().stream()
                    .map(RcaAnalysisEntity::getId)
                    .collect(Collectors.toSet());

            List<RcaCandidateEntity> rcaCandidates = rcaCandidateRepository.findByAnalysisIdInOrderByRankAsc(analysisIds);
            Map<UUID, List<RcaCandidateEntity>> candidatesByAnalysisId = rcaCandidates.stream()
                    .collect(Collectors.groupingBy(RcaCandidateEntity::getAnalysisId));

            for (Map.Entry<UUID, RcaAnalysisEntity> entry : latestRcaByIncident.entrySet()) {
                List<RcaCandidateEntity> candidates = candidatesByAnalysisId.getOrDefault(entry.getValue().getId(), Collections.emptyList());
                String cause = null;
                for (RcaCandidateEntity candidate : candidates) {
                    if (Boolean.TRUE.equals(candidate.getPrimaryCandidate()) && candidate.getCandidateCause() != null) {
                        cause = candidate.getCandidateCause();
                        break;
                    }
                }
                if (cause == null && !candidates.isEmpty() && candidates.get(0).getCandidateCause() != null) {
                    cause = candidates.get(0).getCandidateCause();
                }
                if (cause != null) {
                    primaryRcaCauseByIncident.put(entry.getKey(), cause);
                }
            }
        }

        // 7. Assemble immutable IncidentSignatures preserving candidate corpus order
        List<IncidentSignature> signatures = new ArrayList<>(incidents.size());
        for (IncidentEntity incident : incidents) {
            ResourceEntity resource = resourceMap.get(incident.getResourceId());
            String resourceName = (resource != null) ? resource.getName() : "unknown";
            ResourceType resourceType = (resource != null) ? resource.getType() : ResourceType.SERVICE;

            Set<String> anomalousMetrics = evidenceByIncidentId.getOrDefault(incident.getId(), Collections.emptySet());
            Set<String> topologyTokens = topologyByResourceId.getOrDefault(incident.getResourceId(), Collections.emptySet());
            String primaryRcaCause = primaryRcaCauseByIncident.getOrDefault(incident.getId(), incident.getRootCause());

            signatures.add(new IncidentSignature(
                    incident.getId(),
                    incident.getResourceId(),
                    resourceName,
                    resourceType,
                    incident.getSeverity(),
                    anomalousMetrics,
                    topologyTokens,
                    primaryRcaCause,
                    incident.getDetectedAt(),
                    incident.getResolvedAt()
            ));
        }

        return signatures;
    }
}
