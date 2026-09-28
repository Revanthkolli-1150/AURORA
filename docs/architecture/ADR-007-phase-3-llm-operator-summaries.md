# ADR-007: Phase 3 — LLM Operator Summaries & Runbook Synthesis Architecture

## Status
ACCEPTED

## Date
2026-09-27

---

## 1. Context

AURORA has successfully implemented and verified its foundational deterministic reliability layers:
- **Phase 1A–1D**: Resource inventory, metric telemetry ingestion, statistical anomaly detection (Z-score & MAD), and incident temporal correlation.
- **Phase 2A–2D-A**: Topological dependency graphs, deterministic Root Cause Analysis (RCA) with multi-signal evidence scoring, historical incident intelligence (Jaccard similarity), and empirical graph edge weighting (Empirical Bayes shrinkage).
- **Phase 2D-B**: Supervised candidate scoring was formally deferred under [ADR-006](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-006-phase-2d-ml-assisted-rca.md) due to the absence of authentic operator-feedback ground-truth labels.

The Phase 3 Discovery pass ([`phase-3-discovery-and-architecture-contract.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/phase-3-discovery-and-architecture-contract.md)) classified Phase 3 as **UNDER-SPECIFIED** due to five critical architectural contradictions and several unspecified operational contracts:
1. **Phase Roadmap Ambiguity**: Historical [ADR-001](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-001-modular-control-plane.md) referred to Phase 3 as "Kafka-based telemetry extraction", whereas [ADR-002](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md) and current roadmaps define Phase 3 as "LLM Operator Summaries".
2. **Runtime Placement Contradiction**: [ADR-003](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md) envisioned out-of-process extraction to Python (`intelligence/ai-engine`), whereas ADR-001 and current architecture maintain an in-process Java 21 / Spring Boot modular monolith.
3. **ArchUnit Test Suite Guardrail Conflict**: [`ArchitectureRulesTest.java:214`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java) globally scans all classes in `com.aurora.platform` and rejects any class name containing `"llm"`, `"openai"`, or `"langchain"`.
4. **Runbook Scope Collision with Phase 4**: Uncontrolled "runbook synthesis" risked generating actionable recovery commands, bypassing Phase 4 recovery planning and [ADR-005](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-005-safety-boundaries-for-future-autonomous-actions.md) safety policies.
5. **RCA Summary Field Collision**: `rca_analyses.summary` ([`V4__rca_schema.sql`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/resources/db/migration/V4__rca_schema.sql)) is already reserved for deterministic Phase 2B template summaries.

This ADR resolves these contradictions, establishes the canonical Phase 3 architecture contract, and specifies the technical implementation boundaries.

---

## 2. Decision: Roadmap & Phase Numbering Resolution

1. **Canonical Phase Roadmap**:
   - The capability-based roadmap established in [ADR-002](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-002-deterministic-intelligence-before-ml-llm.md), [ADR-004](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md), [ADR-006](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-006-phase-2d-ml-assisted-rca.md), and [`current-architecture.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/architecture/current-architecture.md) is the **sole authoritative platform roadmap**.
   - **Phase 3 is canonically defined as: LLM Operator Summaries & Runbook Synthesis** (read-only narrative explanations consuming deterministic Phase 2 evidence).
2. **Supersession of ADR-001 Section 4**:
   - ADR-001 Section 4 ("Evolution Strategy: The Path to Microservices") line 32 is formally **SUPERSEDED** regarding phase numbering.
   - Telemetry service extraction is decoupled from Phase 3 numbering and reclassified as an operational scaling trigger governed exclusively by the criteria in [ADR-003](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md) (triggered when telemetry ingestion exceeds 10,000 metrics/sec).

---

## 3. Authority Boundary

AURORA enforces a strict separation between the **Authoritative Deterministic System of Record** and the **Non-Authoritative Generative Explanation**:

```
[ Authoritative Deterministic Plane (ACID Database of Record) ]
  ├── Resource Inventory & Health     ──> com.aurora.platform.resource
  ├── Telemetry Events & Series       ──> com.aurora.platform.telemetry
  ├── Statistical Anomaly Evidence    ──> com.aurora.platform.intelligence.anomaly
  ├── Incident State Machine (FSM)    ──> com.aurora.platform.incident
  ├── Dependency Topology Graph       ──> com.aurora.platform.dependency
  ├── Root Cause Candidates & Ranking ──> com.aurora.platform.intelligence.rca
  ├── Primary Root Cause Invariant    ──> com.aurora.platform.intelligence.rca
  ├── Historical Similarity Scores    ──> com.aurora.platform.intelligence.historical
  └── Empirical Graph Edge Weights    ──> com.aurora.platform.intelligence.graph
                                                      │
                                                      │ (Factual Structured Payload)
                                                      ▼
[ Non-Authoritative Generative Plane (Advisory Only) ]
  └── Phase 3 LLM Operator Narrative  ──> com.aurora.platform.intelligence.narrative
```

### Invariants:
1. **Explanatory Only**: The LLM output has zero operational authority. It is an advisory layer designed to accelerate human cognitive triage.
2. **Primary Candidate Invariant Preservation**:
   - In Phase 2B ([`rca.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/reliability/rca.md)), a candidate cannot be PRIMARY unless `evidenceScore >= 0.30` AND the resource is confirmed anomalous.
   - The LLM narrative must **never** designate an alternative resource as primary root cause, contradict the deterministic primary candidate, or declare certainty when Phase 2B confidence is low or unconfirmed.
3. **No State Mutations**: Generated text cannot be written back to `incidents.status`, `incidents.root_cause`, or `rca_analyses.summary`.

---

## 4. Runtime / Deployment Boundary

We evaluated two placement models:
- **Option A (In-Process Java Modular Monolith)**: Implementing Phase 3 inside `platform/aurora-control-plane` using an outbound HTTP port.
- **Option B (Out-of-Process Python Service Extraction)**: Extracting `intelligence/ai-engine` as a separate Python microservice.

### Decision: In-Process Modular Monolith with Hexagonal Port
AURORA adopts **Option A (In-Process)**:
1. **Adherence to ADR-001 & ADR-003**: An external LLM call is an I/O network boundary, not a runtime divergence. Calling an LLM REST API does not require a local Python execution environment or GPU infrastructure.
2. **Zero Operational Overhead**: Eliminates premature multi-container distributed complexity, container meshes, and gRPC wire contracts during early platform phases.
3. **Context Assembly Efficiency**: Context aggregation across incidents, resources, RCA analyses, candidates, and historical matches executes in-memory within the control plane with zero cross-network latency.
4. **Future-Proof Extraction Path**: If private, on-premise model execution requires a Python runtime in the future, the out-of-process service will implement the exact wire protocol expected by Phase 3's outbound port, ensuring zero changes to application logic.

---

## 5. Provider Boundary & Hexagonal Abstraction

AURORA mandates complete decoupling from specific proprietary AI vendors:
1. **No Proprietary Vendor SDKs**:
   - `pom.xml` must NOT add vendor SDKs (no `openai-java`, `google-cloud-aiplatform`, `langchain4j`, or `spring-ai`).
   - The implementation uses standard Spring 6 / Java 21 HTTP infrastructure (`RestClient` or `HttpClient`).
2. **Hexagonal Port Definition**:
   Located in `com.aurora.platform.intelligence.narrative.application.port.out`:
   ```java
   public interface LlmClientPort {
       LlmGenerationResult generate(LlmPromptRequest request);
   }
   ```
3. **Standard Wire Protocol (OpenAI-Compatible Chat Completions)**:
   - The default infrastructure adapter connects via the industry-standard `POST /v1/chat/completions` REST wire protocol.
   - This single adapter supports OpenAI, Azure OpenAI, Google Cloud Vertex AI (via OpenAI endpoint), AWS Bedrock proxy, Ollama, vLLM, and LiteLLM.
4. **Configuration Boundary** (`application.yml`):
   ```yaml
   aurora:
     intelligence:
       narrative:
         enabled: true
         base-url: "${LLM_BASE_URL:https://api.openai.com/v1}"
         api-key: "${LLM_API_KEY:}"
         model: "${LLM_MODEL:gpt-4o-mini}"
         timeout-ms: 5000
         max-retries: 1
         temperature: 0.0
   ```

---

## 6. ArchUnit & Package Guardrail Alignment

### Defect Identified in ArchitectureRulesTest
[`ArchitectureRulesTest.java:214`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java) checks `importedClasses.stream().anyMatch(...)` globally across `com.aurora.platform`, inadvertently blocking any package from using standard names like `LlmClientPort`.

### Decision:
1. **Rule P1 Scoping**: Rule P1 must be scoped strictly to `..intelligence.graph..` to protect Bayesian graph math from generative concepts.
2. **New Dedicated Phase 3 Architecture Rules**:
   - **Rule Q1 (Domain Purity)**: `com.aurora.platform.intelligence.narrative.domain..` and `.application..` must not reference vendor-specific terms (`openai`, `anthropic`, `gemini`).
   - **Rule Q2 (Infrastructure Isolation)**: Wire-protocol classes (e.g. `OpenAiCompatibleLlmAdapter`) are restricted exclusively to `..infrastructure.adapter..`.
   - **Rule Q3 (Safety Boundary)**: `..intelligence.narrative..` must NOT depend on `..recovery..`, `milvus`, `pinecone`, `pgvector`, or embedding libraries.

---

## 7. Safety Boundary & Runbook Scope

To prevent collisions with Phase 4 Recovery Planning ([ADR-004](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-004-explainable-rca-before-autonomous-recovery.md)) and Autonomous Actions ([ADR-005](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-005-safety-boundaries-for-future-autonomous-actions.md)):

1. **Strictly Informational Runbooks**:
   - Phase 3 "runbook synthesis" is defined exclusively as **Human-Review Verification Checklists** formatted as markdown text.
   - Examples: Verification queries, diagnostic log grep patterns, confirmation steps for on-call SREs.
2. **Prohibited in Phase 3**:
   - No executable action objects (no `actionType: RESTART_POD`).
   - No shell script execution or Kubernetes API integration.
   - No autonomous actuation or recovery triggering.
   - No persistence into `recovery_plans` or `recovery_actions` tables.

---

## 8. Input Contract

Phase 3 consumes an immutable, strongly-typed internal DTO aggregated by the application service:

```java
package com.aurora.platform.intelligence.narrative.application.dto;

public record IncidentNarrativeContext(
    UUID incidentId,
    String incidentTitle,
    String incidentDescription,
    IncidentSeverity severity,
    IncidentStatus status,
    Instant detectedAt,
    UUID investigatedResourceId,
    String investigatedResourceName,
    ResourceType investigatedResourceType,
    String environment,
    UUID rcaAnalysisId,
    Double rcaConfidence,
    String rcaConfidenceLevel,
    String deterministicRcaSummary,
    RcaCandidateSummary primaryCandidate,
    List<RcaCandidateSummary> topSecondaryCandidates,
    List<HistoricalIncidentSummary> similarHistoricalIncidents,
    List<String> upstreamDependencies,
    List<String> downstreamDependents,
    Instant generatedAt
) {}
```

- **Rule**: Direct repository queries from within the LLM adapter are strictly forbidden. All data must be passed via `IncidentNarrativeContext`.

---

## 9. Output Contract & Persistence Policy

### 1. Zero Overwriting of Deterministic RCA State
`rca_analyses.summary` ([`V4__rca_schema.sql`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/resources/db/migration/V4__rca_schema.sql)) is reserved for Phase 2B deterministic template strings and must **NEVER** be overwritten.

### 2. Output Schema DTO
```java
package com.aurora.platform.intelligence.narrative.dto;

public record IncidentNarrativeResponse(
    UUID id,
    UUID incidentId,
    UUID rcaAnalysisId,
    String headline,
    String executiveSummary,
    List<String> observedSymptoms,
    String rootCauseExplanation,
    List<String> secondaryHypothesesEvaluated,
    String historicalContextNarrative,
    List<String> suggestedInvestigationSteps,
    List<String> caveatsAndUncertainties,
    NarrativeMetadataResponse metadata
) {}
```

### 3. Dedicated Relational Schema (Flyway V6)
To satisfy enterprise auditability and sub-millisecond retrieval on dashboard load, narrative outputs are persisted in a new table:

```sql
-- V6__incident_narratives.sql (Specified for Phase 3 Implementation)
CREATE TABLE incident_narratives (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    rca_analysis_id UUID NOT NULL REFERENCES rca_analyses(id) ON DELETE CASCADE,
    headline VARCHAR(255) NOT NULL,
    executive_summary TEXT NOT NULL,
    observed_symptoms JSONB NOT NULL,
    root_cause_explanation TEXT NOT NULL,
    secondary_hypotheses JSONB NOT NULL,
    historical_context TEXT,
    investigation_steps JSONB NOT NULL,
    caveats JSONB NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    generation_duration_ms INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_incident_narratives_analysis UNIQUE (rca_analysis_id)
);

CREATE INDEX idx_incident_narratives_incident ON incident_narratives(incident_id);
```

---

## 10. Prompt Governance

1. **Version-Controlled Classpath Assets**:
   Prompts reside in `src/main/resources/prompts/` (e.g. `operator-narrative-v1.st`) with semantic version identifiers.
2. **Grounding Directives**:
   - Instructs the model to act as an SRE assistant strictly bound by provided JSON context.
   - Explicitly instructs: *"The deterministic primary cause is {primaryCause}. You must explain this finding using the provided evidence. You are strictly forbidden from inventing unlisted resources, metrics, or causes."*
   - Mandates strict JSON schema adhering to `IncidentNarrativeResponse`.
3. **Reproducibility**:
   - `temperature: 0.0` enforced by default to minimize output variance.

---

## 11. Security & Data Redaction

Prior to serialization for the outbound LLM port, all payloads pass through an in-process `DataSanitizer`:

| Category | Policy | Action |
| :--- | :---: | :--- |
| **Passwords / Tokens / Secrets** | **PROHIBIT** | Regex scrubber sanitizes any credential patterns. |
| **Customer Identifiers / PII** | **PROHIBIT** | Dropped or rejected; not allowed in telemetry or incident context. |
| **Private IP Addresses** | **REDACT** | Masked to `ip-10-xxx-xxx` or replaced with logical resource name. |
| **Physical Hostnames** | **REDACT** | Masked to logical resource name (e.g. `cart-service-worker-1`). |
| **Resource Logical Names** | **ALLOW** | Transmitted as domain context (`order-service`, `postgres-db`). |
| **Metric Names & Values** | **ALLOW** | Transmitted as evidence (`cpu_usage`, `active_connections`). |
| **Incident Timestamps & Severities** | **ALLOW** | Transmitted as factual temporal context. |

---

## 12. Failure Semantics & Graceful Degradation

**LLM failure must never compromise system availability or deterministic RCA.**
- **Timeout**: 5000ms hard ceiling via Java HTTP client. Max 1 retry.
- **Circuit Breaker**: If 3 consecutive calls fail or timeout, the adapter trips open for 60 seconds.
- **Deterministic Fallback**:
  - If the LLM provider fails, times out, rate limits, or returns invalid JSON, the service immediately generates a **Deterministic Fallback Narrative**:
    - `headline`: Formatted from `incidents.title` and severity.
    - `executiveSummary`: Constructed directly from `rca_analyses.summary`.
    - `rootCauseExplanation`: Synthesized from top candidate evidence items (`RcaEvidenceResponse.explanation`).
    - `fallbackUsed`: Marked `true` in metadata.
  - The API returns `200 OK` with the fallback narrative; the operator interface never fails.

---

## 13. API Boundary

Two versioned endpoints under `/api/v1/incidents`:

1. `POST /api/v1/incidents/{incidentId}/narrative`:
   - Checks if a narrative already exists for the latest `rca_analysis_id`.
   - If present and `force != true`, returns the persisted narrative immediately (sub-10ms).
   - If absent, aggregates context, calls `LlmClientPort`, persists the result, and returns `201 Created`.
2. `GET /api/v1/incidents/{incidentId}/narrative`:
   - Returns the latest persisted narrative (`200 OK`) or `404 Not Found` if no narrative has been generated yet.

---

## 14. Observability

1. **Metrics (Micrometer)**:
   - `aurora.intelligence.narrative.requests` (counter tagged by `status: SUCCESS | FALLBACK | ERROR`).
   - `aurora.intelligence.narrative.duration` (timer tracking end-to-end execution).
   - `aurora.intelligence.narrative.llm_duration` (timer tracking provider network latency).
   - `aurora.intelligence.narrative.tokens` (counter tracking `prompt` and `completion` tokens).
2. **Structured Logging**:
   - SLF4J MDC tags: `correlationId`, `incidentId`, `rcaAnalysisId`, `provider`, `model`, `promptVersion`, `fallbackUsed`.

---

## 15. Consequences

### Positive
- Delivers rich, natural-language incident explanations and operator checklists while preserving 100% of Phase 2 deterministic authority.
- Completely vendor-agnostic with zero third-party SDK dependencies.
- Sub-10ms response times for repeated queries via dedicated PostgreSQL persistence.
- Zero risk of cascading outages due to hard timeouts and deterministic fallbacks.
- Establishes a clean, audited safety boundary that protects future Phase 4/5 recovery modules.

### Negative
- Requires introducing a Flyway `V6` migration when Phase 3 implementation begins.
- External LLM API calls incur variable network latency (1–4 seconds) on the initial cold generation request.
- Requires maintenance of prompt templates as domain models evolve.

---

## 16. Rejected Alternatives

1. **Using LangChain4j or Spring AI**: Rejected to avoid heavy transient dependency graphs, rapid breaking API churn, and framework bloat. A lightweight HTTP port using standard Java 21 / Spring 6 REST tools provides maximum control and long-term stability.
2. **Overwriting `rca_analyses.summary`**: Rejected because `rca_analyses.summary` is the immutable deterministic baseline summary. Replacing it with stochastic generative text violates ADR-002.
3. **Out-of-Process Python Service Extraction in Phase 3**: Rejected per ADR-003. Generating text via REST endpoints does not justify premature distributed microservice overhead.
4. **Vector Database / Embedding Storage (pgvector/Pinecone)**: Rejected per ADR-002 and ArchUnit Rule N. Context is small, structured, and deterministically assembled in-memory from PostgreSQL.
