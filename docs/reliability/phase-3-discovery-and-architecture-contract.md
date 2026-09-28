# Phase 3 Discovery & Architecture Contract: LLM Operator Summaries & Runbook Synthesis

---

## 1. Executive Status

- **Phase**: Phase 3 — LLM Operator Summaries
- **Current Status**: **UNDER-SPECIFIED — DO NOT IMPLEMENT**
- **Architecture Gate Decision**: **BLOCKED FROM IMPLEMENTATION** until formal Architecture Decision Records (ADRs) resolve architectural placement contradictions, define the external/in-process LLM boundary, specify the API and output contracts, and resolve the ArchUnit guardrail conflicts.
- **Production Code Modified**: **NO**
- **Database Schema Modified**: **NO**

---

## 2. Repository Sources Inspected

This discovery pass inspected and cross-referenced the following repository sources:

### 2.1. Architectural Specifications
- [`docs/architecture/current-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/current-architecture.md): Status of Phases 1A–2D-A, module boundaries, system overview, and operational guarantees.
- [`docs/architecture/module-boundaries.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/module-boundaries.md): Modular monolith encapsulation rules, cross-domain repository prohibitions, and domain ownership matrix.
- [`docs/architecture/target-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/target-architecture.md): Target repository layout, long-term evolutionary extraction strategy, and service definitions (`intelligence/ai-engine`).
- [`docs/architecture/control-plane-v0.1.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/control-plane-v0.1.md): Initial request lifecycle, database schema design, and API boundaries.
- [`docs/architecture/system-overview.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/system-overview.md): Closed-loop reliability feedback architecture (SLO governance, synthetic canaries, chaos testing).
- [`docs/ai/README.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/ai/README.md): High-level description of `intelligence/ai-engine` (AI Copilot Engine).

### 2.2. Architecture Decision Records (ADRs)
- [`docs/adr/ADR-001-modular-control-plane.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-001-modular-control-plane.md): Modular monolith adoption and evolution strategy.
- [`docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md): Foundational requirement for deterministic statistical intelligence prior to introducing ML or LLMs.
- [`docs/adr/ADR-003-controlled-future-service-extraction.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md): Extraction criteria for independent services and runtime environment divergence triggers.
- [`docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md): Explainability prerequisites and deferral of recovery planning to Phase 4 and execution to Phase 5.
- [`docs/adr/ADR-005-safety-boundaries-for-future-autonomous-actions.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-005-safety-boundaries-for-future-autonomous-actions.md): Blast radius controls, cooldowns, and mandatory safety policy boundaries.
- [`docs/adr/ADR-006-phase-2d-ml-assisted-rca.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-006-phase-2d-ml-assisted-rca.md): Separation of Phase 2D-A (empirical graph edge weighting) from deferred Phase 2D-B (supervised candidate scoring).

### 2.3. Phase Reliability Specifications
- [`docs/reliability/anomaly-detection.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/anomaly-detection.md): Z-score and Median Absolute Deviation (MAD) detectors.
- [`docs/reliability/incident-correlation.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/incident-correlation.md): Incident lifecycle, temporal clustering, and resource lock registry.
- [`docs/reliability/rca.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/rca.md): 1-hop topological neighborhood traversal, evidence scoring, candidate ranking.
- [`docs/reliability/historical-incident-intelligence.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/historical-incident-intelligence.md): Discrete set-theoretic similarity matching across metric sets and topology tokens.
- [`docs/reliability/phase-2d-correction-decision.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/phase-2d-correction-decision.md): Empirical Bayes shrinkage edge weighting proof, architecture, and verification.

### 2.4. Source Code & Architecture Guardrails
- [`ArchitectureRulesTest.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java): Rules A through P, particularly Rule F (no recovery execution classes) and Rule P1 (prohibiting LLM, vector search, or OpenAI references).
- Existing Phase 1 and Phase 2 implementations in `platform/aurora-control-plane/src/main/java/com/aurora/platform/`:
  - `intelligence/rca/`: [`RcaAnalysisServiceImpl.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/service/RcaAnalysisServiceImpl.java), [`RcaAnalysisResponse.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/dto/RcaAnalysisResponse.java), [`RcaCandidateResponse.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/dto/RcaCandidateResponse.java), [`RcaEvidenceResponse.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/dto/RcaEvidenceResponse.java).
  - `intelligence/historical/`: [`HistoricalIncidentService.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/historical/service/HistoricalIncidentService.java), [`SimilarIncidentResponse.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/historical/dto/SimilarIncidentResponse.java).
  - `intelligence/graph/`: [`EmpiricalEdgeWeightProvider.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/graph/service/EmpiricalEdgeWeightProvider.java), [`EmpiricalEdgeWeightCalculator.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/graph/domain/EmpiricalEdgeWeightCalculator.java).
  - `incident/`: [`Incident.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/domain/Incident.java), [`IncidentResponse.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/dto/IncidentResponse.java), [`IncidentStatus.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/domain/IncidentStatus.java).

---

## 3. Current Phase 3 Definition

Across the repository, Phase 3 is characterized by three divergent definitions:

1. **High-Level Functional Definition** ([`current-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/current-architecture.md)):
   > *"Read-only incident summaries and runbook synthesis consuming deterministic Phase 2 RCA evidence."*
2. **Evolutionary Intelligence Definition** ([`ADR-002`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md)):
   > *"Phase 3 introduces LLM-assisted operator summaries only after the underlying factual evidence chain is already proven."*
3. **Out-of-Process Target Architecture Definition** ([`target-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/target-architecture.md) & [`ADR-003`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md)):
   > *"Python/ML/LLM integration via bounded gRPC/REST APIs or service adapters for incident summarization and runbook assistance,"* housed in an extracted service [`intelligence/ai-engine/`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/ai/README.md).

*(Note: [`ADR-001`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-001-modular-control-plane.md) line 32 also defines "Phase 3" as telemetry service extraction via Apache Kafka, creating a historical phase-numbering contradiction with ADR-002).*

---

## 4. Scope

Based on explicit repository evidence, the following capabilities are identified as **IN SCOPE** for Phase 3:

| Capability | Status | Evidence Source | Description |
| :--- | :---: | :--- | :--- |
| **Incident Summarization** | **IN SCOPE** | ADR-002, `current-architecture.md`, `target-architecture.md` | Synthesizing a human-readable natural-language summary from factual incident metadata, detected timestamps, resource context, and observed metric anomalies. |
| **RCA Explanation** | **IN SCOPE** | ADR-002, `rca.md`, `current-architecture.md` | Translating deterministic Phase 2B/2D candidate rankings and multi-signal evidence chains (temporal precedence, dependency relationships, directional telemetry correlations) into clear narrative explanations for SRE operators. |
| **Historical Context Narrative** | **IN SCOPE** | `historical-incident-intelligence.md`, `current-architecture.md` | Synthesizing contextual narratives explaining why previously resolved incidents were flagged as structurally similar by Phase 2C and how those past incidents were resolved. |

---

## 5. Non-Scope

The following capabilities are explicitly **OUT OF SCOPE** or **BLOCKED / DEFERRED** based on architectural boundaries:

| Capability | Status | Rationale & Evidence |
| :--- | :---: | :--- |
| **Autonomous Decision Making** | **OUT OF SCOPE** | Strictly forbidden by [`ADR-002`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md) and [`ADR-004`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md). LLM must never decide incident status, select root causes, or trigger state transitions. |
| **Autonomous Remediation / Actuation** | **OUT OF SCOPE** | Gated for Phase 5 under [`ADR-004`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md) and [`ADR-005`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-005-safety-boundaries-for-future-autonomous-actions.md). Forbidden by ArchUnit Rule F. |
| **Structured Recovery Planning** | **BLOCKED / DEFERRED** | Gated for Phase 4 ([`ADR-004`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md)). Generating executable recovery plans (`recovery_plans`, `recovery_actions`) requires the `policy` safety engine, which is not yet active. |
| **Supervised Candidate Scoring (Phase 2D-B)** | **BLOCKED / DEFERRED** | Excluded under [`ADR-006`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-006-phase-2d-ml-assisted-rca.md) due to lack of authentic operator feedback ground truth. |
| **Vector Search / Embeddings / ANN** | **OUT OF SCOPE** | Prohibited by [`historical-incident-intelligence.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/historical-incident-intelligence.md) and ArchUnit Rules N and P1. Factual grounding is achieved via direct relational context injection, not vector retrieval. |
| **State Mutation on Incidents or Resources** | **OUT OF SCOPE** | Phase 3 is strictly read-only. Calling Phase 3 cannot mutate `incidents`, `resources`, `telemetry_events`, or `resource_dependencies`. |

---

## 6. Authority Model

The authority model across AURORA's intelligence layers is strictly hierarchical and deterministic:

```
[ Authoritative Deterministic Plane ]
  ├── Resource State & Inventory    ──> com.aurora.platform.resource (PostgreSQL 'resources')
  ├── Metric Telemetry Data          ──> com.aurora.platform.telemetry (PostgreSQL 'telemetry_events')
  ├── Anomaly Evidence               ──> com.aurora.platform.intelligence.anomaly (Z-Score / MAD)
  ├── Incident Lifecycle State       ──> com.aurora.platform.incident (FSM State Machine)
  ├── Dependency Topology            ──> com.aurora.platform.dependency ('resource_dependencies')
  ├── Root Cause Candidates & Rank   ──> com.aurora.platform.intelligence.rca (Phase 2B Evidence Engine)
  ├── Primary Root Cause Designation ──> com.aurora.platform.intelligence.rca (Primary Invariant: Anomaly req.)
  ├── Historical Similarity Matches  ──> com.aurora.platform.intelligence.historical (Jaccard Matching)
  └── Dependency Edge Weights        ──> com.aurora.platform.intelligence.graph (Empirical Bayes Shrinkage)
                                                      │
                                                      │ (Strict Read-Only Context Injection)
                                                      ▼
[ Non-Authoritative Generative Plane ]
  └── Phase 3 LLM Operator Summary   ──> EXPLANATORY ONLY (Advisory natural-language narrative)
```

### Invariants:
1. **The LLM is Explanatory Only**:
   - The LLM has **zero authority** over system state, incident status, anomaly scores, candidate rankings, or primary root-cause designations.
2. **Phase 2B Primary Cause Invariant**:
   - In Phase 2B ([`rca.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/rca.md)), a candidate cannot be PRIMARY unless it has `evidenceScore >= 0.30` AND confirmed anomalous evidence (`ANOMALY` or `TELEMETRY_CORRELATION`).
   - The LLM narrative must **never** designate an alternative resource as the primary root cause or contradict the deterministic primary candidate output.
3. **No Authority Injection**:
   - Generated natural language cannot be fed back into deterministic services as inputs or state variables.

---

## 7. Input Contract

Phase 3 operates exclusively by consuming validated, strongly typed DTOs produced by existing domain services. The repository already provides these authoritative contracts:

| Domain Aggregate | Existing DTO Source | Key Consumed Fields | Authoritative? | Sensitive? |
| :--- | :--- | :--- | :---: | :---: |
| **Target Incident** | [`IncidentResponse`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/dto/IncidentResponse.java) | `id`, `resourceId`, `title`, `description`, `severity`, `status`, `detectedAt`, `resolvedAt` | YES | YES (internal metadata) |
| **Investigated Resource** | `ResourceResponse` | `id`, `name`, `type`, `status`, `environment`, `host` | YES | YES (internal hostnames) |
| **RCA Analysis** | [`RcaAnalysisResponse`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/dto/RcaAnalysisResponse.java) | `id`, `status`, `summary` (Phase 2B deterministic summary), `confidence`, `confidenceLevel`, `candidates` | YES | NO |
| **RCA Candidates** | [`RcaCandidateResponse`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/dto/RcaCandidateResponse.java) | `candidateResourceId`, `candidateMetric`, `candidateCause`, `evidenceScore`, `rank`, `primaryCandidate`, `evidence` | YES | NO |
| **RCA Evidence Items** | [`RcaEvidenceResponse`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/dto/RcaEvidenceResponse.java) | `evidenceType`, `resourceId`, `metricName`, `observedValue`, `anomalyScore`, `observedAt`, `contributionScore` | YES | NO |
| **Historical Matches** | [`SimilarIncidentResponse`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/historical/dto/SimilarIncidentResponse.java) | `historicalIncidentId`, `resourceName`, `similarityScore`, `breakdown`, `primaryRcaCause`, `resolutionDurationSeconds` | YES | NO |
| **Directed Topology** | `DependencyResponse` | Upstream dependencies and downstream dependents of investigated resource | YES | YES (architecture map) |

### Missing Input Contract:
- **UNSPECIFIED**: The repository contains **no unified input aggregate DTO** (e.g. `IncidentNarrativeContext` or `LlmPromptPayload`) that bundles these disparate service DTOs for consumption.

---

## 8. Output Contract

### Current Repository State:
- The repository defines **no DTO, record, or entity** representing Phase 3 output.
- `rca_analyses.summary` ([`RcaAnalysisEntity.java:46`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/entity/RcaAnalysisEntity.java)) is already dedicated to the Phase 2B deterministic summary template (`"RCA analysis completed. Primary candidate root cause: %s..."`).

### Status: **UNSPECIFIED**
The output schema for Phase 3 is completely unspecified. An implementation-ready contract must define:
1. `narrativeSummary`: High-level explanation of the incident and impact.
2. `symptomsObserved`: Bulleted breakdown of anomalies across the investigated resource.
3. `rootCauseExplanation`: Natural-language justification explaining why the Phase 2 primary candidate was identified, citing temporal and graph evidence.
4. `historicalContext`: Comparison notes highlighting resolutions from similar past incidents.
5. `investigationChecklist`: Informational verification steps for on-call engineers.
6. `provenance`: Audit metadata detailing model identifier, prompt version, generation timestamp, and deterministic input hash.

---

## 9. LLM Provider Boundary

### Current Repository State:
- **Provider Choice**: **UNSPECIFIED**. No external provider (OpenAI, Anthropic, Google Gemini, Bedrock) or local inference engine (Ollama, vLLM) has been chosen or approved.
- **Library Dependencies**: `pom.xml` contains **zero** AI or LLM framework dependencies (no Spring AI, LangChain4j, or HTTP client wrappers).
- **Application Port**: **UNSPECIFIED**. No `LlmClient` or `TextGenerationPort` interface exists.
- **Provider Reliability Controls**:
  - Timeouts: **UNSPECIFIED**
  - Rate limiting & circuit breaking: **UNSPECIFIED**
  - Token limits & window management: **UNSPECIFIED**
  - Structured output enforcement (JSON schema): **UNSPECIFIED**

### Status: **UNSPECIFIED & BLOCKED**
Phase 3 cannot be implemented until an architectural port is defined and approved via an ADR.

---

## 10. Prompt Contract

### Current Repository State:
- **Prompt Templates**: **UNSPECIFIED**. No prompt text, system prompts, or Jinja/Mustache templates exist in the repository.
- **System Instructions**: **UNSPECIFIED**. No rules instructing the model on tone, grounding boundaries, or forbidden assumptions exist.
- **Asset Storage & Versioning**: **UNSPECIFIED**. It is undecided whether prompts should be version-controlled classpath resources (e.g. `src/main/resources/prompts/operator-summary-v1.st`), externalized configuration, or database records.

---

## 11. Grounding & Hallucination Boundary

### Architectural Requirement:
Per [`ADR-002`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md) and [`rca.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/rca.md), AURORA requires zero risk of hallucinations during incident triage. The LLM must be strictly constrained by the provided factual context:

1. **Forbidden Extrapolations**:
   - Must NOT invent unobserved metrics or fabricated anomaly scores.
   - Must NOT name resources outside the supplied incident, topology, and historical context.
   - Must NOT contradict the deterministic Phase 2B primary candidate.
   - Must NOT assert root causes when Phase 2B marked the analysis as unconfirmed (`summary = "No sufficiently supported root-cause candidate..."`).
2. **Missing Grounding Enforcement Mechanisms**:
   - **UNSPECIFIED**: How grounding is verified at runtime (e.g. JSON schema constraint, prompt few-shotting, post-generation citation validation, or deterministic fallback) is completely undefined.

---

## 12. Failure Semantics

### Core Architectural Invariant:
**LLM unavailability or failure must never degrade or block core reliability operations.** Deterministic anomaly detection, incident management, RCA ranking, and historical similarity must remain 100% operational regardless of LLM health.

### Missing Concrete Semantics:
- **UNSPECIFIED**:
  - Fallback payload when the LLM returns an error, times out, or hits a rate limit (e.g. fallback to Phase 2B template summary).
  - Timeout thresholds (e.g. 3000ms max for interactive API calls).
  - Retry policy (zero retries vs single retry with exponential backoff).
  - Error reporting to the frontend (HTTP 200 with partial degradation flag vs HTTP 503).

---

## 13. Persistence

### Current Repository State:
- The database schema (Flyway `V1` through `V5`) contains **no tables** for LLM responses, prompts, tokens, or audit trails.
- `rca_analyses.summary` is already reserved for deterministic Phase 2B summaries.

### Architectural Decision Required:
- **Option A (Ephemeral / Query-Time Derivation)**: Phase 3 narratives are computed on-demand upon operator request and not stored in PostgreSQL.
- **Option B (Persisted Summaries)**: A new relational migration (`V6__incident_operator_narratives.sql`) creates a dedicated table storing:
  - `incident_id` (FK -> `incidents.id`)
  - `analysis_id` (FK -> `rca_analyses.id`)
  - `narrative_json` (JSONB / TEXT)
  - `model_name` (VARCHAR)
  - `prompt_version` (VARCHAR)
  - `generation_duration_ms` (INTEGER)
  - `created_at` (TIMESTAMPTZ)
- **Status: UNSPECIFIED**. The repository does not specify whether Phase 3 output is persisted.

---

## 14. API Contract

### Current Repository State:
- No REST endpoint exists or is documented for Phase 3.
- [`control-plane-v0.1.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/control-plane-v0.1.md) lists `/api/v1/incidents/{incidentId}/recovery-plan` (Phase 4 recovery), but nothing for LLM summaries.

### Status: **UNSPECIFIED**
The following specifications must be formally decided:
- Path: e.g. `GET /api/v1/incidents/{id}/summary` or `POST /api/v1/incidents/{id}/narrative`
- Invocation model: Synchronous REST vs Server-Sent Events (SSE) streaming
- Caching & idempotency semantics: Whether repeated calls re-execute the LLM or return cached summaries.

---

## 15. Security & Data Handling

### Security Risks of External LLM Calls:
Monitored infrastructure telemetry contains sensitive operational intelligence:
- Internal hostnames and private IP addresses
- Environment classification (`PRODUCTION`, `PCI-STAGING`)
- Internal service topology and dependencies
- Exception stack traces and incident descriptions

### Status: **UNSPECIFIED**
The repository defines **no data redaction, token masking, or sanitization rules** prior to passing payloads to an external LLM. Enterprise security governance requires:
1. Data classification of fields sent across the network.
2. Mandatory redaction of credentials, environment variables, and customer IDs.
3. Decision on cloud-hosted SaaS LLM vs self-hosted on-premise model.

---

## 16. Module Placement & Dependencies

### Contradiction in Architectural Placement:

```
Proposed Path A (In-Process Spring Boot Monolith)     Proposed Path B (Extracted Python Service)
------------------------------------------------     -----------------------------------------
platform/aurora-control-plane                        intelligence/ai-engine/
└── com.aurora.platform.intelligence.narrative       ├── FastAPI / gRPC server
    ├── application/port/in/                          ├── LangChain / vLLM / LiteLLM
    ├── domain/                                      └── Python runtime (ADR-003)
    └── infrastructure/adapter/ (LLM HTTP Client)
```

1. **In-Process Modular Monolith Path**:
   - Favored by [`ADR-001`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-001-modular-control-plane.md) and [`current-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/current-architecture.md) (single deployable unit).
   - Module placement: `com.aurora.platform.intelligence.narrative` or `com.aurora.platform.intelligence.summary`.
   - **BLOCKED BY ARCHUNIT**: `ArchitectureRulesTest.java` line 214 strictly fails if any class name contains `"llm"`, `"openai"`, or `"langchain"`.
2. **Out-of-Process Service Path**:
   - Favored by [`ADR-003`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md) ("Intelligence Service Extraction: Triggered in Phase 2D/Phase 3 when ML/AI workloads require Python runtimes") and [`target-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/target-architecture.md) (`intelligence/ai-engine`).
   - Requires setting up a multi-process Docker Compose environment, networking, and gRPC/REST wire contracts.

### Status: **CONTRADICTION — DECISION REQUIRED**

---

## 17. Phase 2 Integration Boundary

Phase 3 is an unprivileged consumer of Phase 1 and Phase 2:
- **Allowed Inbound Dependencies**:
  - `com.aurora.platform.incident.service.IncidentService` (or read-only port)
  - `com.aurora.platform.intelligence.rca.service.RcaAnalysisService`
  - `com.aurora.platform.intelligence.historical.service.HistoricalIncidentService`
  - `com.aurora.platform.dependency.service.DependencyService`
  - `com.aurora.platform.resource.service.ResourceService`
- **Forbidden Dependencies**:
  - Phase 3 must **never** inject foreign `JpaRepository` classes directly (violates ADR-001 & ArchUnit Rule B).
  - Phase 3 must **never** mutate Phase 2 entities or call repository `.save()` on foreign domains.
  - Phase 2 must **never** depend on Phase 3 (RCA must remain completely independent of the LLM layer).

---

## 18. Autonomy & Safety Boundary

Cross-checking [`ADR-004`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md), [`ADR-005`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-005-safety-boundaries-for-future-autonomous-actions.md), and [`ArchitectureRulesTest.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java) Rule F:

| Action / Capability | Permitted in Phase 3? | Enforcement Mechanism |
| :--- | :---: | :--- |
| **Mutate Incident Status** | **NO** | Incident state machine (`IncidentStatus`) |
| **Restart Pod / Process** | **NO** | ArchUnit Rule F (bans `RecoveryExecutor`, `KubernetesActuator`) |
| **Execute Shell Command** | **NO** | ArchUnit Rule F (bans `ShellExecutor`) |
| **Trigger Auto-Rollback** | **NO** | ArchUnit Rule F (bans `AutoRollback`) |
| **Generate Structured Action Plan** | **NO** | Gated for Phase 4 (`recovery_plans` under `policy` governance) |
| **Synthesize Informational Runbook Text** | **YES (Advisory Only)** | Strictly passive markdown text for human engineer guidance |

> [!CAUTION]
> If Phase 3 generates runbook steps, they must be formatted exclusively as human-readable narrative text. Under no circumstances may Phase 3 output executable command payloads or integrate with automated actuators.

---

## 19. Observability

### Current Repository State:
- `CorrelationIdFilter` binds an `X-Correlation-ID` to SLF4J MDC for structured request tracing across Spring Boot logs.

### Status: **UNSPECIFIED**
Phase 3 requires specific observability instrumentation that is not yet designed:
- Metric: LLM call duration (`aurora.intelligence.llm.duration_seconds`)
- Metric: Token consumption (`aurora.intelligence.llm.tokens.prompt`, `aurora.intelligence.llm.tokens.completion`)
- Metric: Error counts (`aurora.intelligence.llm.errors`, tagged by error type: `TIMEOUT`, `RATE_LIMIT`, `PARSE_FAILURE`)
- Structured logging: Prompt version and model ID tagged in log statements.

---

## 20. Determinism

AURORA mandates a strict distinction between **Deterministic Facts** and **Generative Language**:

| Dimension | Deterministic Facts (Phase 1 & 2) | Generative Language (Phase 3) |
| :--- | :--- | :--- |
| **Components** | Incident timestamps, metric names, anomaly scores, candidate rankings, primary candidate UUID, historical similarity scores. | Narrative summaries, sentence structure, explanatory analogies, formatted checklists. |
| **Reproducibility** | **100% Exact & Reproducible**. Identical inputs yield identical bit-level numbers and ranks every run. | **Non-Deterministic**. LLM outputs may vary across runs unless pinned with temperature=0 and deterministic model seeds. |
| **System Authority** | **Authoritative System of Record**. Enforced in database tables and transactional state machines. | **Non-Authoritative**. Purely advisory for human operators. |
| **Failure Tolerance** | Zero tolerance for state inconsistency. ACID transactions enforced. | Graceful degradation. If generative language fails, deterministic facts remain fully available. |

---

## 21. Contradiction Audit

The discovery pass uncovered the following explicit contradictions across repository documentation, ADRs, and tests:

| Source A | Source B | Contradiction | Impact | Required Decision |
| :--- | :--- | :--- | :--- | :--- |
| **`ADR-001` (Line 32)** | **`ADR-002` (Line 32) & `current-architecture.md`** | `ADR-001` defines Phase 3 as **Kafka-based Telemetry Extraction**, whereas `ADR-002` and architecture roadmaps define Phase 3 as **LLM Operator Summaries**. | Ambiguity in phase scope and evolution roadmap. | Formally supersede `ADR-001` Section 4 to align Phase 3 numbering with `ADR-002` and Phase 2D deliverables. |
| **`ADR-003` (Line 40) & `target-architecture.md`** | **`ADR-001` (Line 19) & `current-architecture.md`** | `ADR-003` triggers **out-of-process extraction to Python (`intelligence/ai-engine`)** for Phase 3, whereas `ADR-001` and current architecture enforce an **in-process Java modular monolith**. | Unclear architectural placement and technology stack for Phase 3. | Issue an ADR deciding whether Phase 3 is an in-process Java Spring Boot module or an extracted Python microservice. |
| **`ArchitectureRulesTest.java` (Line 214)** | **Phase 3 Java Implementation Proposals** | ArchUnit Rule P1 scans all classes in `com.aurora.platform` and strictly asserts `isFalse()` if any class name contains `"llm"`, `"openai"`, or `"langchain"`. | Adding any standard Java LLM client or service class in the control plane causes test suite failure. | Must update ArchUnit rules to scope Rule P1 specifically to `intelligence.graph` while defining separate rules for Phase 3. |
| **`current-architecture.md` ("Runbook Synthesis")** | **`ADR-004` (Line 30) & `ADR-005` (Line 19)** | "Runbook synthesis" implies generating actionable recovery steps, which directly collides with Phase 4 recovery planning and ADR-005 safety policy boundaries. | Risk of premature recovery action generation bypassing safety policies. | Formally define Phase 3 runbooks as purely descriptive/informational checklists for human operators. |
| **`rca_analyses.summary` (`V4__rca_schema.sql`)** | **Phase 3 Summary Storage Proposals** | `rca_analyses.summary` is already populated by Phase 2B deterministic template strings. Storing LLM output here would overwrite deterministic state. | Potential corruption of deterministic RCA state. | Prohibit writing LLM outputs to `rca_analyses.summary`; establish separate schema or ephemeral query model. |

---

## 22. Architectural Invariants

Based on discovery findings, the following 10 invariants are established for Phase 3:

| Invariant | Status | Rationale |
| :--- | :---: | :--- |
| **1. Phase 3 is strictly read-only** | **ACCEPTED** | LLM operations cannot mutate incidents, resources, topology, or telemetry. |
| **2. Phase 2 deterministic evidence remains authoritative** | **ACCEPTED** | Anomaly scores, candidate rankings, primary candidate designations, and graph weights are immutable inputs. |
| **3. LLM output cannot mutate incident lifecycle** | **ACCEPTED** | State transitions (`DETECTED` -> `RESOLVED`) remain strictly governed by the incident state machine. |
| **4. LLM output cannot overwrite deterministic RCA state** | **ACCEPTED** | `rca_analyses.summary` and candidate records must remain untouched. |
| **5. LLM failure cannot invalidate deterministic RCA** | **ACCEPTED** | System gracefully degrades to deterministic presentation if LLM is unavailable. |
| **6. No autonomous recovery occurs in Phase 3** | **ACCEPTED** | Recovery actions are strictly prohibited per ADR-004, ADR-005, and ArchUnit Rule F. |
| **7. No vector database or embeddings are introduced** | **ACCEPTED** | Grounding relies strictly on deterministic relational context injection. |
| **8. No Phase 2 scoring semantics are changed** | **ACCEPTED** | Phase 1C, 2B, 2C, and 2D-A algorithms and constants remain completely untouched. |
| **9. Existing module boundaries remain intact** | **ACCEPTED** | No cross-domain repository calls; interaction occurs via strongly typed DTOs. |
| **10. Generated text must have provenance and grounding constraints** | **ACCEPTED** | Narratives must cite factual evidence and include model/prompt version metadata. |

---

## 23. Missing Decisions

Before Phase 3 can transition to implementation, the following concrete decisions must be formally approved:

1. **Architectural Placement & Runtime Stack**:
   - In-process Java 21 / Spring Boot 3 service (e.g. using Spring AI or lightweight REST client) vs out-of-process Python service (`intelligence/ai-engine`) via gRPC/REST.
2. **LLM Provider & Model Selection**:
   - Choice of provider (OpenAI, Anthropic, Google Cloud Vertex AI, or local Ollama/vLLM) and specific model family.
3. **Guardrail Alignment in `ArchitectureRulesTest.java`**:
   - Refactoring line 214 of `ArchitectureRulesTest` so that the ban on `"llm"`/`"openai"` is scoped strictly to `intelligence.graph` and `intelligence.rca`, permitting approved Phase 3 packages.
4. **Output Schema & Persistence Policy**:
   - Explicit definition of the output DTO.
   - Decision on ephemeral calculation vs persistence in a new Flyway migration (`V6`).
5. **Runbook Semantic Boundaries**:
   - Clear contract specifying that runbook synthesis produces passive informational text only, with no executable action objects.
6. **Data Redaction & Security Policy**:
   - Identification of required sanitization routines for hostnames, environments, and sensitive configuration.

---

## 24. Implementation Readiness Gate

### Final Classification:
# **UNDER-SPECIFIED — DO NOT IMPLEMENT**

### Justification:
1. **Critical Architectural Contradictions**: The repository directly contradicts itself regarding whether Phase 3 is an in-process Java component or an extracted Python service (ADR-001 vs ADR-003 vs current architecture).
2. **ArchUnit Guardrail Conflict**: Existing test suite rules in `ArchitectureRulesTest.java` actively prohibit LLM-related classes across the control plane.
3. **Missing Critical Contracts**: Provider choice, application ports, prompt templates, output DTOs, API endpoints, persistence strategy, and data sanitization policies are completely undefined.
4. **Safety Boundaries**: The concept of "runbook synthesis" lacks a formal contract distinguishing it from Phase 4 recovery planning.

---

## 25. Recommended Next Decision

To safely advance Phase 3 toward implementation, execute the following steps in sequence:

1. **Draft and Accept ADR-007 (Phase 3 Architecture & LLM Integration)**:
   - Formally decide between an in-process Spring Boot client vs an extracted service.
   - Clarify Phase 3 naming across all ADRs (superseding ADR-001 Section 4).
   - Specify the approved LLM provider, timeout defaults, and credential handling.
   - Define runbook synthesis as purely descriptive informational text.
2. **Define Formal Inbound/Outbound DTO Contracts**:
   - Design `IncidentNarrativeRequest` and `IncidentNarrativeResponse` contracts.
3. **Align Architecture Guardrails**:
   - Refactor `ArchitectureRulesTest.java` Rule P1 to ensure Phase 3 packages have clear, dedicated ArchUnit boundaries without triggering false positives.
4. **Conduct Pre-Implementation Review**:
   - Once ADR-007 is accepted and contracts are frozen, proceed to Phase 3 implementation.

---

## 26. Architecture Resolution Status (Post ADR-007)

Following the initial discovery pass that classified Phase 3 as **UNDER-SPECIFIED**, the architectural contradictions and missing decisions were formally analyzed and resolved in [ADR-007 (Phase 3 LLM Operator Summaries & Runbook Synthesis Architecture)](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/ADR-007-phase-3-llm-operator-summaries.md).

The table below documents the final resolution status for every discovery finding:

| Missing Decision / Contradiction | Initial Status | ADR-007 Reference | Canonical Architectural Resolution |
| :--- | :---: | :--- | :--- |
| **Phase Roadmap & Numbering** | `CONTRADICTION` | ADR-007 §2 | **SUPERSEDED**: ADR-001 §4 is superseded. Phase 3 is canonically defined as **LLM Operator Summaries & Runbook Synthesis**. Telemetry extraction is decoupled from Phase 3 and governed solely by ADR-003 throughput triggers. |
| **Runtime Placement & Boundary** | `CONTRADICTION` | ADR-007 §4 | **RESOLVED (In-Process Monolith)**: Implemented in `platform/aurora-control-plane` under package `com.aurora.platform.intelligence.narrative`. Outbound network calls are mediated via a clean hexagonal port (`LlmClientPort`), preserving monolith simplicity while enabling future out-of-process extraction if needed. |
| **LLM Provider & Abstraction** | `UNSPECIFIED` | ADR-007 §5 | **RESOLVED (Provider-Neutral Hexagonal Port)**: Zero vendor SDK dependencies (no OpenAI, no LangChain, no Spring AI in `pom.xml`). Outbound adapter implements standard `POST /v1/chat/completions` protocol via Spring 6 / Java 21 `HttpClient`, compatible with OpenAI, Azure, Vertex AI, and local vLLM/Ollama. |
| **ArchUnit Guardrail Conflict** | `CONTRADICTION` | ADR-007 §6 | **RESOLVED (Scoped Rules)**: Diagnosed global check defect in `ArchitectureRulesTest.java:214`. Rule P1 is scoped specifically to `intelligence.graph`. New Rules Q1, Q2, and Q3 protect narrative domain purity, isolate infrastructure adapters, and prevent dependency on recovery execution. |
| **Authority Model & Invariants** | `UNSPECIFIED` | ADR-007 §3 | **RESOLVED (Explanatory Only)**: Phase 2 deterministic RCA and incident state machine remain the sole authoritative system of record. Phase 2B primary root-cause invariant is immutable; LLM cannot alter candidate rankings, invent causes, or mutate incident state. |
| **Runbook Scope & Boundary** | `CONTRADICTION` | ADR-007 §7 | **RESOLVED (Informational Only)**: Runbooks in Phase 3 are strictly defined as human-review verification checklists formatted as markdown text. Executable recovery actions and autonomous actuation are strictly prohibited and gated for Phase 4/5 per ADR-004/005. |
| **Input Contract** | `UNSPECIFIED` | ADR-007 §8 | **RESOLVED**: Defined canonical aggregate `IncidentNarrativeContext` consuming validated DTOs from `incident`, `resource`, `rca`, `historical`, and `dependency`. Direct foreign repository queries are prohibited. |
| **Output Contract & Schema** | `UNSPECIFIED` | ADR-007 §9 | **RESOLVED**: Defined canonical `IncidentNarrativeResponse` (headline, executiveSummary, observedSymptoms, rootCauseExplanation, secondaryHypotheses, historicalContext, investigationSteps, caveats, metadata). `rca_analyses.summary` is strictly protected from overwrite. |
| **Persistence Policy** | `UNSPECIFIED` | ADR-007 §9 | **RESOLVED (Dedicated Schema)**: Output is persisted in a dedicated relational table `incident_narratives` via future Flyway migration `V6__incident_narratives.sql`. Provides full enterprise auditability and sub-10ms cached reads. |
| **Prompt Governance & Versioning** | `UNSPECIFIED` | ADR-007 §10 | **RESOLVED**: Version-controlled prompt templates in `src/main/resources/prompts/` (e.g. `operator-narrative-v1.st`) with explicit grounding directives, JSON schema constraints, and default `temperature: 0.0`. |
| **Security & Data Sanitization** | `UNSPECIFIED` | ADR-007 §11 | **RESOLVED (Data Classification Matrix)**: Mandatory in-process `DataSanitizer` regex scrubber strips passwords, tokens, and PII. Hostnames and private IPs are masked. Resource logical names, metric names, and evidence scores are allowed. |
| **Failure Semantics & Degradation**| `UNSPECIFIED` | ADR-007 §12 | **RESOLVED (Deterministic Fallback)**: Hard 5000ms timeout with circuit breaker. If LLM fails or times out, system automatically generates a deterministic fallback narrative from Phase 2B template text. API returns `200 OK` with `fallbackUsed = true`. Zero impact on RCA. |
| **API Boundary** | `UNSPECIFIED` | ADR-007 §13 | **RESOLVED**: `POST /api/v1/incidents/{incidentId}/narrative` (generate or retrieve) and `GET /api/v1/incidents/{incidentId}/narrative` (retrieve latest). Idempotent cached responses under 10ms. |
| **Observability Telemetry** | `UNSPECIFIED` | ADR-007 §14 | **RESOLVED**: Micrometer timers for generation and LLM latency; counters for requests, errors, and token consumption; SLF4J MDC correlation ID propagation across all log lines. |

### Post-Resolution Readiness Gate:
With the acceptance of **ADR-007**, all critical architectural contradictions, provider boundaries, safety constraints, and DTO contracts are fully specified and internally consistent.

**Final Phase 3 Architectural Classification: `IMPLEMENTATION-READY`**
*(Implementation may proceed upon user approval following the step-by-step plan: ArchUnit rule alignment -> Flyway V6 migration -> Port & Domain Models -> Infrastructure Adapter -> Application Service & Controller).*
