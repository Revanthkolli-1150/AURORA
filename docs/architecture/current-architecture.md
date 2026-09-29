# AURORA Current Architecture

## 1. System Overview
AURORA is an autonomous reliability platform engineered to observe software systems, understand failures, detect anomalies, investigate root causes, predict incidents, plan safe recovery, execute controlled recovery, verify results, and learn from incidents.

In the current implementation phase (Phases 1A through 2D-A complete; Phase 2D-B deferred), the backend is deployed as a single, highly cohesive **Modular Monolith**:
- **Runtime**: Java 21 LTS, Spring Boot 3.3.4
- **Persistence**: PostgreSQL 16.x via Spring Data JPA and Flyway migrations (V1 through V5)
- **Deployment Artifact**: Single executable jar (`platform/aurora-control-plane`)

```
+-----------------------------------------------------------------------------------------------------------------+
|                                              AURORA Control Plane                                               |
|                                                                                                                 |
|   +-------------------+    +--------------------+    +--------------------+                                     |
|   |     resource      |    |     telemetry      |    |     dependency     |                                     |
|   |  (Registry, State,|    | (Metric Ingestion, |    | (Directed Topology,|                                     |
|   |   Lifecycle)      |    |  Time-Series)      |    |  Up/Downstream)    |                                     |
|   +---------+---------+    +---------+----------+    +---------+----------+                                     |
|             ^                        ^                         ^                                                |
|             |                        |                         |                                                |
|             +------------------------+-------------------------+                                                |
|                                      |                                                                          |
|                        +-------------+-------------+                                                            |
|                        |                           |                                                            |
|                        v                           v                                                            |
|             +--------------------+       +------------------------------------------------------------------+   |
|             |      incident      | <---> |                           intelligence                           |   |
|             | (Lifecycle, State, |       | +--------------------------------------------------------------+ |   |
|             |  Correlation)      |       | | 1C: anomaly (Statistical Z-score & MAD Detectors)            | |   |
|             +--------------------+       | +--------------------------------------------------------------+ |   |
|                                          | | 2B: rca (Deterministic 1-Hop Neighborhood Evidence Engine)   | |   |
|                                          | +--------------------------------------------------------------+ |   |
|                                          | | 2C: historical (Set-Theoretic Historical Similarity Matching)| |   |
|                                          | +--------------------------------------------------------------+ |   |
|                                          | | 2D-A: graph (Empirical Bayes Dependency Edge Weighting)      | |   |
|                                          | +--------------------------------------------------------------+ |   |
|                                          +------------------------------------------------------------------+   |
|                                                                                                                 |
|   +---------------------------------------------------------------------------------------------------------+   |
|   | common / infrastructure (Web Filters, Global Exceptions, Correlation ID, JPA Auditing, ArchUnit Rules)  |   |
|   +---------------------------------------------------------------------------------------------------------+   |
+-----------------------------------------------------------------------------------------------------------------+
                                                |
                                                v
                             +-------------------------------------+
                             |       PostgreSQL 16 Database        |
                             |  (Flyway Migrations V1, V2, V3, V4, |
                             |   V5 - Relational Schema)           |
                             +-------------------------------------+
```

---

## 2. Implemented Modular Architecture

The backend codebase inside `platform/aurora-control-plane/src/main/java/com/aurora/platform/` is organized into canonical, bounded domain packages adhering strictly to hexagonal boundaries and ArchUnit guardrails:

| Module Package | Phase | Responsibility | Primary APIs / Components |
| :--- | :---: | :--- | :--- |
| `com.aurora.platform.resource` | 1A | Resource registration, status lifecycle, filtering by environment/type/status. | `/api/v1/resources` |
| `com.aurora.platform.telemetry` | 1B | Metric telemetry ingestion, validation, chronological querying by resource. | `/api/v1/telemetry` |
| `com.aurora.platform.intelligence.anomaly` | 1C | Statistical anomaly detection (Z-score & MAD detectors) with asymptotic score normalization. | Configurable via `aurora.intelligence.anomaly.detector` |
| `com.aurora.platform.incident` | 1D, 4B | Incident state machine, anomaly correlation, evidence linking, and manual incident creation. | `/api/v1/incidents` (GET, POST, PATCH) |
| `com.aurora.platform.dependency` | 2A | Directed dependency graph modeling (`sourceResource DEPENDS_ON targetResource`). | `/api/v1/resources/{id}/dependencies` |
| `com.aurora.platform.intelligence.rca` | 2B | Deterministic root cause analysis across direct topological neighborhood and temporal evidence. | `/api/v1/incidents/{id}/rca` |
| `com.aurora.platform.intelligence.historical` | 2C | Historical Incident Intelligence identifying structurally similar resolved incidents across the fleet. | `/api/v1/incidents/{id}/similar` |
| `com.aurora.platform.intelligence.graph` | 2D-A | Empirical graph intelligence computing learned dependency edge weights via Empirical Bayes shrinkage. | Injected via `EdgeWeightProvider` into RCA |
| `com.aurora.platform.intelligence.narrative` | 3 | Explanatory LLM operator summaries & runbook checklists with deterministic fallback. | `/api/v1/incidents/{id}/narrative` |
| `com.aurora.platform.policy` | 4A | Operational reliability guardrails, threshold policies, and production safety constraints. | `/api/v1/policies` |
| `com.aurora.platform.recovery` | 4A, 4B | Deterministic recovery plan synthesis, pre-flight policy evaluation, anti-flapping cooldown, and action approval. | `/api/v1/incidents/{id}/recovery-plan` |
| `com.aurora.platform.common` | Core | Shared infrastructure: correlation ID filters, global exception handlers, standardized API envelopes. | `common.web`, `common.exception`, `common.api` |

---

## 3. Current Phase Status Summary

- **Phase 1A (Resource Inventory & Health)**: COMPLETE & VERIFIED
- **Phase 1B (Telemetry Ingestion & Pipeline)**: COMPLETE & VERIFIED
- **Phase 1C (Statistical Anomaly Detection)**: COMPLETE & VERIFIED
- **Phase 1D (Incident Correlation Engine)**: COMPLETE & VERIFIED
- **Phase 2A (Resource Dependency Topology)**: COMPLETE & VERIFIED
- **Phase 2B (Deterministic Root Cause Analysis)**: COMPLETE & VERIFIED
- **Phase 2C (Historical Incident Intelligence)**: COMPLETE & VERIFIED
- **Phase 2D-A (Empirical Graph Intelligence / Learned Edge Weighting)**: COMPLETE & VERIFIED
- **Phase 2D-B (Supervised Candidate Scoring)**: BLOCKED / DEFERRED (Awaiting operator feedback ground truth)
- **Phase 3 (LLM Operator Summaries & Runbook Synthesis)**: COMPLETE & VERIFIED
- **Phase 4A (Deterministic Recovery Planning & Human Approval Scaffolding)**: COMPLETE & VERIFIED
- **Phase 4B (Pre-Flight Policy Evaluation, Anti-Flapping Cooldown & Manual Incidents)**: COMPLETE & VERIFIED
- **Phase 5 (Guarded Autonomous Actuation & Post-Recovery Verification)**: UPCOMING (Guarded by ADR-005 and Rule F)


---

## 4. Current Operational & Architectural Guarantees

1. **Deterministic & Explainable**:
   - Zero black-box ML models, zero non-deterministic LLM agents in diagnostic paths.
   - All anomaly scores, RCA evidence scores, similarity ranks, and graph weights evaluate to identical outputs on identical inputs.
2. **Strict Invariant Safety**:
   - A candidate resource cannot become PRIMARY in RCA unless it possesses active anomalous evidence (`ANOMALY` or `TELEMETRY_CORRELATION`). Learned graph edge weights refine ranking among anomalous candidates but can never make a healthy dependency the primary root cause.
3. **Relational Integrity**:
   - Full ACID transactional guarantees across PostgreSQL 16 relational tables (Flyway V1–V6). No schema migrations were needed for Phase 2C, Phase 2D-A, Phase 4A, or Phase 4B.
4. **Hexagonal Modularity & Inversion**:
   - Phase 2B deterministic RCA does not depend on Phase 2D graph implementation classes. It integrates cleanly via the `EdgeWeightProvider` port, falling back to static prior $0.20$ if the graph provider is absent or experiences errors.
5. **Architectural Guardrails (ArchUnit)**:
   - Enforced by `ArchitectureRulesTest.java` (25 rules, Rules A through Q8), preventing layer leakage, forbidden dependencies on recovery execution (Rule F), and premature introduction of vector databases or external AI dependencies.
6. **Phase 4 Operational Reliability & Safety Guarantees**:
   - **Advisory & Proposal Only**: Phase 4A and Phase 4B generate deterministic, explainable recovery plan proposals. They have zero autonomous execution authority.
   - **Rule F Preservation**: No actuators, no Kubernetes mutation clients, no cloud provider mutation APIs, no shell execution engines, and no background remediation workers exist.
   - **Pre-Flight Policy Evaluation**: Before any action is proposed, active reliability policies are evaluated. Prohibited or threshold-exceeding actions are converted to `MANUAL_INVESTIGATION` with risk escalated to `CRITICAL` and human approval required.
   - **Anti-Flapping Cooldown**: Suppresses repeated failed actions (2 failures within rolling 1-hour window for the same target and action type), resetting only upon confirmed success.
   - **Manual Incident Creation**: Supported via `POST /api/v1/incidents` with full Jakarta validation and standard 201 Created semantics.
   - **Human Approval Semantics**: Human operators can approve actions via `POST /api/v1/incidents/{id}/recovery-plan/actions/{actionId}/approve`. Approval transitions the action status to `APPROVED` with defense-in-depth policy and cooldown verification, but NEVER executes the action.
   - **Phase 5 Guarded Actuation**: Actual automated or semi-automated actuation is strictly future work deferred to Phase 5 under ADR-005.
