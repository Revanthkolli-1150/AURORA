# ADR-006: Phase 2D — Empirical Graph Intelligence & Edge Weighting for RCA

## Status
ACCEPTED (Phase 2D-A Implemented & Verified; Phase 2D-B Blocked / Deferred)

## Date
2026-09-27

---

## 1. Context
AURORA’s reliability control plane diagnoses system incidents using topological, temporal, and telemetry evidence.

- **Phase 1C & 1D**: Established deterministic anomaly detection and incident correlation.
- **Phase 2A**: Introduced directed resource dependencies (`u DEPENDS_ON v` via `resource_dependencies`).
- **Phase 2B**: Built deterministic Root Cause Analysis (RCA) evaluating candidates within a 1-hop neighborhood using static evidence weights (`WEIGHT_ANOMALY = 0.40`, `WEIGHT_TEMPORAL_PRECEDENCE = 0.25`, `WEIGHT_DEPENDENCY = 0.20`, `WEIGHT_TELEMETRY_CORRELATION = 0.15`).
- **Phase 2C**: Implemented Historical Incident Intelligence to identify structurally similar resolved incidents across the fleet using discrete set-theoretic matching.

In real-world distributed architectures, not all dependency edges carry equal risk of failure propagation. A service may depend on a database that frequently causes cascading outages upon latency spikes, while also depending on an internal logging proxy that almost never triggers incident-level degradation. A static dependency weight ($0.20$) treats every architectural edge identically.

---

## 2. Problem
Phase 2D was envisioned to introduce "ML-Assisted RCA" and "Graph Weighting". However, initial proposals conflated two distinct capabilities:
1. **Empirical Graph Weighting**: Dynamically estimating failure propagation rates along dependency edges based on observable telemetry and anomaly co-occurrence history.
2. **Supervised Candidate Scoring**: Training a machine learning classifier ($S_{\text{ML}} = \sigma(\mathbf{w}^T \mathbf{x} + b)$) to predict root-cause candidates based on labeled outcomes.

The repository currently possesses **zero trustworthy operator-confirmed ground-truth labels**. Training a supervised model on Phase 2B heuristic flags (`rca_candidates.primary_candidate`) would create circular bias, violating ADR-002 and ADR-004. Furthermore, initial proposals introduced opaque composite scoring formulas ($0.60 S_{\text{det}} + 0.40 S_{\text{ML}}$) and asynchronous timeout machinery (`CompletableFuture.orTimeout`) without empirical justification.

---

## 3. Current Phase 2B Contract
In Phase 2B (`RcaAnalysisServiceImpl.java`):
1. **Investigation Scope**: 1-hop topological neighborhood around investigated resource $u$ (direct dependencies, self, direct dependents).
2. **Evidence Weights**: Additive sum bounded in $[0.00, 1.00]$:
   - `ANOMALY`: $0.40$
   - `TEMPORAL_PRECEDENCE`: $0.25$
   - `DEPENDENCY`: $0.20$ (static)
   - `TELEMETRY_CORRELATION`: $0.15$
3. **Primary Selection Invariant**: Candidate must have `evidenceScore >= 0.30` AND possess verified anomalous evidence (`ANOMALY` or `TELEMETRY_CORRELATION`).
4. **Ranking**: `evidenceScore DESC`, then `precedenceSeconds ASC`, then `candidateResourceId ASC`.

---

## 4. Relationship to Phase 2C
Phase 2C and Phase 2D operate on fundamentally different datasets and problem formulations:
- **Phase 2C (Global Incident Similarity)**: Queries a globally bounded fleet-wide corpus of the most recent 200 `RESOLVED` incidents to find structurally similar past incident signatures.
- **Phase 2D (Local Graph Intelligence)**: Queries the incident history of the specific investigated resource $u$ to estimate pairwise propagation probabilities $P(\text{anomaly}(v) \mid \text{incident}(u))$ on edge $(u, v)$.
- **Population Separation**: Phase 2D does NOT reuse Phase 2C's 200-incident slice. Reusing a fleet-wide window would result in near-zero observations for individual resources, causing widespread cold-start degradation.

---

## 5. Decision
AURORA formally splits Phase 2D into two architectural stages:
1. **Phase 2D-A (Empirical Graph Intelligence)**: **APPROVED**. Replaces the static dependency weight ($0.20$) with a learned, observational propagation rate $w_{\text{edge}}(u, v)$ derived via Empirical Bayes smoothing from historical incident co-occurrences.
2. **Phase 2D-B (Supervised Candidate Scoring)**: **BLOCKED / DEFERRED**. Supervised predictive modeling is excluded until authentic operator feedback mechanisms and labeled datasets exist.
3. **Option A Adopted**: Logistic regression scoring ($S_{\text{ML}}$) and linear score blending ($S_{\text{composite}}$) are rejected. The learned edge weight enters the existing deterministic RCA engine directly through `RcaEvidenceType.DEPENDENCY`.

---

## 6. Scope of Phase 2D-A
- Applies exclusively to directed dependency relationships (`u DEPENDS_ON v`) evaluated during RCA for investigated resource $u$.
- Operates strictly in-process and synchronously within the control plane.
- Computes $w_{\text{edge}}(u, v)$ using closed-form Empirical Bayes shrinkage.
- Does not modify anomaly detection, temporal precedence, or telemetry correlation logic.
- Does not mutate database schemas (Flyway V1–V5 remain complete and sufficient).

---

## 7. Status of Phase 2D-B (Supervised ML)
Phase 2D-B is formally **BLOCKED and NOT IMPLEMENTATION-READY**.
- Neither `rca_candidates.primary_candidate` (heuristic rule output) nor `incidents.root_cause` (unstructured text) constitute independent ground truth.
- No artificial feedback tables or synthetic labels will be manufactured.
- Implementation of supervised ML is deferred to a future phase following the deployment of authentic human-in-the-loop operational feedback mechanisms.

---

## 8. Data Population Definition
For an incident $I_{\text{target}}$ detected on resource $u$ at timestamp $t_{\text{detected}}$:
- **Historical Population ($H_u$)**: All incidents in `incidents` satisfying:
  1. `resource_id == u` (resource-specific failure history).
  2. `status == 'RESOLVED'` (verified completed incidents).
  3. `id != I_target.id` (self-exclusion).
  4. `detected_at < t_detected` (strict temporal precedence; zero future leakage).
- **Population Bound**: Bounded to the most recent $K_{\text{max}} = 100$ resolved incidents on resource $u$.

---

## 9. Feature & Data Semantics
- **Observation**: An individual historical incident $I_h \in H_u$.
- **$N_{\text{incident}}(u)$**: The total number of qualifying historical resolved incidents on resource $u$:
  $$N_{\text{incident}}(u) = |H_u|$$
- **Anomalous Co-occurrence ($N_{\text{co-occur}}(u, v)$)**: The count of incidents in $H_u$ where upstream dependency $v$ exhibited an anomaly in `incident_anomaly_evidence` during the 10-minute lookback window of $I_h$:
  $$N_{\text{co-occur}}(u, v) = \sum_{I_h \in H_u} \mathbb{I}\left(\text{resource } v \text{ had anomaly in } [I_h.\text{detected\_at} - 10\text{m},\; I_h.\text{detected\_at}]\right)$$

---

## 10. Edge-Weight Formula
The empirical edge weight is calculated using Empirical Bayes shrinkage with a Beta prior:

$$w_{\text{edge}}(u, v) = \frac{N_{\text{co-occur}}(u, v) + \beta \cdot w_0}{N_{\text{incident}}(u) + \beta}$$

Subject to policy clamp bounds:
$$w_{\text{final}}(u, v) = \min\left(w_{\text{max}}, \max\left(w_{\text{min}}, w_{\text{edge}}(u, v)\right)\right)$$

---

## 11. Parameter Classification

| Parameter | Symbol | Default | Classification | Description & Rationale |
| :--- | :---: | :---: | :--- | :--- |
| **Prior Weight** | $w_0$ | `0.20` | **Architecture Policy** | Equals Phase 2B static weight; guarantees seamless cold start. |
| **Smoothing Factor** | $\beta$ | `5.0` | **Configuration** | Pseudo-observation weight balancing prior vs empirical evidence. |
| **Clamp Minimum** | $w_{\text{min}}$ | `0.05` | **Architecture Policy** | Ensures active structural dependencies retain positive presence. |
| **Clamp Maximum** | $w_{\text{max}}$ | `0.40` | **Architecture Policy** | Prevents edge weight from exceeding direct anomaly evidence. |
| **Lookback Window** | $\Delta t$ | `10 min` | **Configuration** | Aligned with `RcaProperties.lookbackMinutes`. |
| **Corpus Limit** | $K_{\text{max}}$ | `100` | **Configuration** | Bounds historical query execution time. |

---

## 12. Score Semantics
- `rca_candidates.evidence_score` maintains its existing domain semantic as an explainable, additive evidence index:
  $$\text{evidenceScore} = \min\left(1.00, \sum \text{contributionScore}_i\right)$$
- The learned edge weight $w_{\text{final}}(u, v)$ is assigned to `rca_evidence.contribution_score` for evidence type `DEPENDENCY`.
- Candidate ranking order, tie-breaking rules, and confidence mapping (`LOW`, `MODERATE`, `HIGH`, `VERY_HIGH`) remain identical to Phase 2B.

---

## 13. Fallback Behavior
- **Cold Start ($N_{\text{incident}}(u) = 0$)**: Evaluates smoothly to $w_0 = 0.20$.
- **Query / Execution Exception**: Caught synchronously in the query adapter; logs a warning and falls back to static $0.20$.
- **API Resilience**: Empirical edge weight failure **never** fails an RCA analysis or HTTP endpoint.

---

## 14. Explainability Contract
Learned edge weights must be completely transparent to human operators. The `rca_evidence.explanation` text must explicitly disclose the empirical values:
```text
auth-service directly depends on user-db (DEPENDS_ON).
Empirical edge weight: 0.28 (prior: 0.20, historical co-occurrences: 4/7 incidents).
```

---

## 15. Persistence Contract
- **No Schema Changes**: Phase 2D-A requires **no database migration**. Flyway V1–V5 schema is complete.
- **Relational Tables Used**:
  - Read: `incidents`, `incident_anomaly_evidence`, `resource_dependencies`.
  - Write: `rca_analyses`, `rca_candidates`, `rca_evidence` (unchanged schema).

---

## 16. API Compatibility
- **100% Backward Compatible**: Existing REST API endpoints (`POST /api/v1/incidents/{id}/rca`, `GET /api/v1/incidents/{id}/rca`) and DTOs (`RcaAnalysisResponse`, `RcaCandidateResponse`, `RcaEvidenceResponse`) are unchanged.
- Field types, precision, and response structures remain identical.

---

## 17. Transaction Behavior
- Edge weight queries are read-only (`@Transactional(readOnly = true)`).
- RCA persistence executes within the existing single write transaction in `RcaAnalysisServiceImpl.analyzeIncident()`.

---

## 18. Performance Targets
- **Single Edge Weight Calculation**: Target $< 1.0\text{ ms}$.
- **Total RCA Execution with Graph Weighting**: Target $< 50.0\text{ ms}$ for 1-hop neighborhood.
- **Database Access**: Exactly 1 batch query for historical co-occurrences; zero per-candidate N+1 queries.
- *Notice*: No measured benchmarks currently exist for Phase 2D. All performance numbers are design targets.

---

## 19. Security Boundaries
- Operates entirely within the authenticated control-plane boundary.
- Read-only data access over internal PostgreSQL tables.
- No outbound network calls, external API dependencies, or cloud services.

---

## 20. Architecture Rules (Proposed Rule P)
ArchUnit guardrail `Rule P` will enforce:
1. `com.aurora.platform.intelligence.graph..` must not import LLM or vector search libraries.
2. `com.aurora.platform.intelligence.graph..` must not depend on `com.aurora.platform.recovery..`.
3. Deterministic Phase 2B must not have compile-time dependencies on Phase 2D implementations.
4. Graph learning must execute strictly in-process.

---

## 21. Non-Goals
- No supervised candidate scoring models (Phase 2D-B).
- No multi-hop recursive graph traversal or BFS graph walks.
- No dynamic mutation of dependency edges in `resource_dependencies`.
- No LLMs, generative summaries, or vector embeddings.
- No autonomous remediation or recovery execution.

---

## 22. Risks & Mitigations
- **Risk**: Sparse incident history causes noisy edge weights.
  - *Mitigation*: Empirical Bayes smoothing with $\beta = 5.0$ and clamp bounds $[0.05, 0.40]$ prevents extreme weights.
- **Risk**: SRE operators assume high edge weight implies fault on a healthy service.
  - *Mitigation*: Strict invariant that candidates without active anomalies cannot be designated as PRIMARY.

---

## 23. Future Prerequisites for Supervised ML (Phase 2D-B)
Before Phase 2D-B can be considered:
1. Create a dedicated `rca_feedback` schema tracking human operator root-cause confirmations.
2. Collect at least $1000$ human-validated incident diagnosis records.
3. Build an offline model validation pipeline with reproducible cross-validation and rollback capabilities.
