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
| `com.aurora.platform.incident` | 1D | Incident state machine, temporal anomaly correlation, evidence linking, and resource locking. | `/api/v1/incidents` |
| `com.aurora.platform.dependency` | 2A | Directed dependency graph modeling (`sourceResource DEPENDS_ON targetResource`). | `/api/v1/resources/{id}/dependencies` |
| `com.aurora.platform.intelligence.rca` | 2B | Deterministic root cause analysis across direct topological neighborhood and temporal evidence. | `/api/v1/incidents/{id}/rca` |
| `com.aurora.platform.intelligence.historical` | 2C | Historical Incident Intelligence identifying structurally similar resolved incidents across the fleet. | `/api/v1/incidents/{id}/similar` |
| `com.aurora.platform.intelligence.graph` | 2D-A | Empirical graph intelligence computing learned dependency edge weights via Empirical Bayes shrinkage. | Injected via `EdgeWeightProvider` into RCA |
| `com.aurora.platform.policy` | Pre-4 | Operational safety constraints, rule definitions, and policy entities. | Internal domain entities and repositories |
| `com.aurora.platform.recovery` | Pre-4/5 | Target domain for future recovery planning and controlled execution. | Planned for Phase 4 / Phase 5 |
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
- **Phase 3 (LLM Incident Summaries)**: UPCOMING
- **Phase 4 (Autonomous Recovery Planning)**: UPCOMING
- **Phase 5 (Controlled Recovery Execution)**: UPCOMING

---

## 4. Current Operational & Architectural Guarantees

1. **Deterministic & Explainable**:
   - Zero black-box ML models, zero non-deterministic LLM agents in diagnostic paths.
   - All anomaly scores, RCA evidence scores, similarity ranks, and graph weights evaluate to identical outputs on identical inputs.
2. **Strict Invariant Safety**:
   - A candidate resource cannot become PRIMARY in RCA unless it possesses active anomalous evidence (`ANOMALY` or `TELEMETRY_CORRELATION`). Learned graph edge weights refine ranking among anomalous candidates but can never make a healthy dependency the primary root cause.
3. **Relational Integrity**:
   - Full ACID transactional guarantees across PostgreSQL 16 relational tables (Flyway V1–V5). No schema migrations were needed for Phase 2C or Phase 2D-A.
4. **Hexagonal Modularity & Inversion**:
   - Phase 2B deterministic RCA does not depend on Phase 2D graph implementation classes. It integrates cleanly via the `EdgeWeightProvider` port, falling back to static prior $0.20$ if the graph provider is absent or experiences errors.
5. **Architectural Guardrails (ArchUnit)**:
   - Enforced by `ArchitectureRulesTest.java` (Rules A through P), preventing layer leakage, forbidden dependencies on recovery execution, and premature introduction of vector databases or external AI dependencies.
