# AURORA Reliability Platform
## Phase 5 Architecture Reconnaissance: Actuation Safety, Verification & Operational Boundaries

---

### Executive Summary

- **Repository**: `C:\Users\user\OneDrive\Desktop\AURORA`
- **Branch**: `main`
- **Baseline Commit**: `e5750ffd15b0934bb18c8d7503aa4ca27fc7e23d` (`feat: implement phase 4 policy-guarded recovery planning`)
- **Working Tree State**: Clean
- **Reconnaissance Scope**: Architecture & design audit of the complete uncommitted and committed codebase across Phases 1A through 4B.
- **Strict Constraint**: Zero production code changes, zero schema migrations, zero actuator implementations, zero autonomous execution introduced.

This report evaluates whether AURORA's control plane architecture is ready for a future **Phase 5 (Guarded Autonomous Actuation & Post-Recovery Verification)**. It systematically maps the end-to-end telemetry and recovery flows, evaluates domain abstractions, audits security and transaction boundaries, identifies architectural gaps, and specifies what must remain strictly impossible before any external mutation authority is considered.

---

### 1. Telemetry Interfaces and Data Flow

#### A. Entry Points
Telemetry enters the control plane through a single synchronous REST endpoint:
- **Class**: `com.aurora.platform.telemetry.controller.TelemetryController`
- **Endpoint**: `POST /api/v1/telemetry`
- **Request DTO**: `IngestTelemetryRequest(UUID resourceId, Instant timestamp, TelemetryType type, String metricName, Double value, String unit, Map<String, Object> metadata)`
- **Validation**: Jakarta `@Valid`, requires non-null finite `Double value`, `TelemetryType.METRIC`, and an existing `resourceId` resolved via `ResourceService.existsById(resourceId)`.

#### B. Telemetry Representation
- **Entity**: `com.aurora.platform.telemetry.entity.TelemetryEventEntity`
- **Enum**: `com.aurora.platform.telemetry.entity.TelemetryType` (`METRIC`, `LOG`, `TRACE`, `EVENT`). In Phase 1B/4B, ingestion is strictly restricted to `TelemetryType.METRIC`.
- **Response DTO**: `com.aurora.platform.telemetry.dto.TelemetryEventResponse`

#### C. Persistence
- **Table**: `telemetry_events` (created in Flyway `V1__init_control_plane_schema.sql`, indexed in `V2__telemetry_indexes.sql`).
- **Repository**: `com.aurora.platform.telemetry.repository.TelemetryEventRepository`.
- **Storage**: Relational ACID storage in PostgreSQL 16 (or H2 in test mode). Events are appended chronologically.

#### D. Flow from Telemetry to RCA
1. An incoming metric event is evaluated by `AnomalyDetectionService.evaluateAnomaly(...)`.
2. Historical window data is retrieved via `telemetryEventRepository.findByResourceIdAndMetricNameOrderByTimestampAsc(...)`.
3. If the detector (`ZScoreAnomalyDetector` or `MadAnomalyDetector`) flags an anomaly (`status == ANOMALOUS`), `AnomalyDetectionServiceImpl` forwards the evaluation to `IncidentCorrelationService.correlateAnomaly(...)`.
4. `IncidentCorrelationServiceImpl` attaches the anomaly as an `IncidentAnomalyEvidenceEntity` in the `incident_anomaly_evidence` table (Flyway `V3`), creating or updating an `IncidentEntity` (status `DETECTED` or `INVESTIGATING`).
5. When `RcaAnalysisService.analyzeIncident(UUID incidentId)` executes, it reads all anomalous evidence from `IncidentAnomalyEvidenceRepository`, traverses resource dependency edges from `ResourceDependencyRepository` (Flyway `V4`), queries empirical edge weights from `EdgeWeightProvider` (Phase 2D-A), and synthesizes ranked `RcaCandidateEntity` and `RcaEvidenceEntity` records (Flyway `V5`).

#### E. Flow from RCA to Recovery Planning
1. `RecoveryService.generateRecoveryPlan(UUID incidentId)` invokes `rcaAnalysisService.getLatestAnalysisByIncidentId(incidentId)`.
2. It extracts the primary candidate (`Boolean.TRUE.equals(candidate.primaryCandidate())`).
3. If confidence is $\ge 0.30$ and primary candidate exists, it maps the candidate metric (`conn`, `pool`, `cpu`, `thread`, `mem`, `heap`, `error`, `latency`) and candidate resource type to candidate action types (`FLUSH_CACHE`, `RESTART_POOL`, `SCALE_OUT`, `RESTART_POD`, `CIRCUIT_BREAKER_ENABLE`, `ROLLBACK`).
4. Each candidate action is evaluated against anti-flapping cooldown (`evaluateAntiFlapping`) and pre-flight policy constraints (`evaluatePolicyPreFlight`).
5. Permitted actions or policy/cooldown-blocked replacements (`MANUAL_INVESTIGATION`) are saved into `recovery_plans` and `recovery_actions` tables (Flyway `V1`).

#### F. Post-Recovery Telemetry Querying
- **Current State**: Gapped.
- `TelemetryEventRepository` defines:
  `List<TelemetryEventEntity> findByResourceIdAndTimestampBetweenOrderByTimestampAsc(UUID resourceId, Instant start, Instant end)`
- However, `TelemetryService` exposes only:
  `getTelemetryByResourceId(UUID resourceId, String metricName)`
  which returns all telemetry chronologically from the beginning of time.
- There is no service method to query telemetry starting from an action's `completedAt` timestamp.

#### G. Observe → Act → Observe Again Abstraction
- **Current State**: Completely missing.
- There is no abstraction, interface, scheduled task, or background processor representing post-action observation, convergence verification, or metric normalization.

#### H. Processing Mechanics
- Telemetry ingestion is strictly **synchronous request-response**, directly persisting to the database.
- There are no streaming message brokers (Kafka, RabbitMQ, Pulsar), no event-driven listeners (`@EventListener`), and no background polling tasks.

#### I. Telemetry Freshness & Staleness Guarantees
- No freshness checks exist. If an external client posts a telemetry event with a timestamp from 2 days ago, it is accepted and persisted without error. There is no sliding-window staleness rejection or heartbeat monitoring.

---

### 2. Resource Abstraction and Resource Types

#### A. Target Identification
- Target resources in `com.aurora.platform.resource.entity.ResourceEntity` are identified by:
  - `id`: UUID (Primary Key, unique)
  - `name`: VARCHAR(255) (Indexed, non-unique)
  - `type`: `ResourceType` enum (`SERVER`, `DATABASE`, `SERVICE`, `CONTAINER`, `APPLICATION`, `POD`)
  - `status`: `ResourceStatus` enum (`HEALTHY`, `DEGRADED`, `CRITICAL`, `UNKNOWN`)
  - `environment`: VARCHAR(50) (e.g. `"production"`, `"staging"`, `"development"`)
  - `host`: VARCHAR(255)
  - `metadata`: TEXT (JSON)
- However, in `RecoveryActionEntity`, target is stored only as:
  `@Column(name = "target", nullable = false) private String target;`
  In `RecoveryServiceImpl`, this string is populated with `resource.name()` (e.g. `"auth-service"`), or falls back to `resourceId.toString()`.

#### B. Cross-Environment Resource Existence & Collision
- The database schema (`V1__init_control_plane_schema.sql`) contains:
  `CREATE INDEX idx_resources_name ON resources (name);`
  There is **no unique constraint** on `(name, environment)` or `name`.
- Therefore, two distinct resources can exist:
  - Resource 1: `id = 1111`, `name = "order-service"`, `environment = "staging"`
  - Resource 2: `id = 2222`, `name = "order-service"`, `environment = "production"`
- In `RecoveryActionRepository`:
  `List<RecoveryActionEntity> findByTargetAndActionType(String target, String actionType)`
  queries strictly by the string `"order-service"`.
- **Critical Risk**: A failure in `staging` collides directly with cooldown state in `production` because `target` is not scoped by resource UUID or environment.

#### C. Unambiguous Target Resolution
- Currently, a `RecoveryActionEntity` cannot be unambiguously resolved back to a `ResourceEntity` purely from its `target` field if duplicate names exist across environments.
- **Phase 5 Requirement**: `RecoveryActionEntity` must store `targetResourceId` (`UUID`, foreign key to `resources.id`) and `targetEnvironment` (`VARCHAR(50)`).

#### D. Decoupling Vendor APIs from Domain
- `ResourceType` is a vendor-neutral domain enum. It contains no Kubernetes, AWS, GCP, or Azure concepts.
- To maintain hexagonal purity in Phase 5, actuators must be modeled as infrastructure adapters implementing a domain port (e.g. `RecoveryActuatorPort`), with dispatching based on `(ResourceType, ActionType)`.

---

### 3. Recovery Action Lifecycle

#### A. States Existing Today
Represented by `com.aurora.platform.recovery.entity.RecoveryActionStatus`:
- `PENDING`
- `APPROVED`
- `RUNNING`
- `SUCCESS`
- `FAILED`
- `ROLLED_BACK`

#### B. State Transitions Enforced in Code
- In `RecoveryServiceImpl`:
  - Plan generation creates actions in `PENDING`.
  - `approveRecoveryAction(UUID incidentId, UUID actionId)` enforces:
    - If status is `APPROVED`: Idempotent no-op (returns existing action).
    - If status is not `PENDING`: Throws `IllegalStateException("Cannot approve recovery action in status: ...")`.
    - If in anti-flapping cooldown: Throws `IllegalStateException`.
    - If policy prohibits action: Throws `IllegalStateException`.
    - Updates status to `APPROVED`.
- **No other transitions exist in production code**. There are no methods to transition to `RUNNING`, `SUCCESS`, `FAILED`, or `ROLLED_BACK`.

#### C. Database Constraints on Transitions
- Schema defines `status VARCHAR(50) NOT NULL`.
- There are **no check constraints**, no database triggers, and no audit tables enforcing sequential state transitions. Any status string up to 50 characters can technically be written by SQL.

#### D. Missing States for Controlled Actuation
A future Phase 5 controlled actuation engine requires:
1. `QUEUED_FOR_EXECUTION`
2. `EXECUTION_IN_PROGRESS`
3. `EXECUTION_COMPLETED`
4. `EXECUTION_TIMED_OUT`
5. `VERIFICATION_IN_PROGRESS`
6. `VERIFIED_SUCCESS`
7. `VERIFIED_REGRESSION`
8. `ROLLBACK_IN_PROGRESS`
9. `CANCELLED`

#### E. Execution-Attempt Identity & Repeated Execution
- `RecoveryActionEntity` represents a single action record.
- It possesses `startedAt` and `completedAt`, but **no execution attempt entity or execution ID**.
- If an action fails and is re-attempted, the original timestamps and results are overwritten, destroying execution audit history.
- There is no `idempotency_key`, no `lease_token`, and no `lock_version` to prevent concurrent worker execution.

---

### 4. Configuration and Environment Model

#### A. Environment Representation
- `application.yml` defines:
  `aurora.control-plane.environment: ${AURORA_ENV:development}`
- Mapped to `com.aurora.platform.infrastructure.configuration.AuroraProperties(String version, String environment)`.

#### B. Environment Isolation & Dangerous Capability Flags
- `AuroraProperties` is currently injected **only** into `ControlPlaneHealthIndicator` to expose environment metadata in actuator health checks.
- It is **not** injected into `RecoveryServiceImpl`, `IncidentServiceImpl`, or `PolicyServiceImpl`.
- There are no configuration properties for:
  - `aurora.recovery.actuation.enabled`
  - `aurora.recovery.actuation.allowed-environments`
  - `aurora.recovery.actuation.dry-run`
  - `aurora.recovery.actuation.timeout-seconds`

#### C. Fail-Closed Posture
- Today, the system is **100% fail-closed** because no execution code exists (Rule F).
- In Phase 5, all actuator configuration must default to:
  - `enabled: false`
  - `dry-run: true`
  - `allowed-environments: ["development", "test"]`
  - Requiring explicit, intentional environment override to enable in `production`.

---

### 5. Staging vs Production Representation

#### A. Ground Truth on Environment Boundary
- **Application Level**: The application knows its runtime environment via `aurora.control-plane.environment`.
- **Resource Level**: Resources store environment as a freeform string in `ResourceEntity.environment`.
- **Recovery Level**:
  In `RecoveryServiceImpl.java` line 343:
  `boolean isProduction = targetResource != null && "production".equalsIgnoreCase(targetResource.environment());`
  `approvalRequired = isProduction || risk == RecoveryRisk.HIGH || risk == RecoveryRisk.CRITICAL || ...;`
- **Gaps**:
  1. `environment` is a loose `String`, not an enum (`Production`, `Staging`, `Development`, `Test`). Typos (e.g. `"prod"`, `"Production"`, `"prd"`) bypass case-insensitive checks against `"production"`.
  2. `RecoveryActionEntity` and `RecoveryPlanEntity` have no `environment` column.
  3. No validation prevents an operator from approving a plan intended for staging against production if targets share a name.

---

### 6. Authentication and Authorization

#### A. Spring Security Absence
- `pom.xml` **does not include** `spring-boot-starter-security`.
- There are no security filters, no `SecurityFilterChain`, no OAuth/JWT processors, no API key interceptors, and no password hashing utilities.

#### B. Endpoint Vulnerability
- Every endpoint in AURORA is completely unauthenticated:
  - `POST /api/v1/incidents`
  - `POST /api/v1/incidents/{id}/recovery-plan`
  - `POST /api/v1/incidents/{id}/recovery-plan/actions/{actionId}/approve`
  - `POST /api/v1/policies`
  - `PATCH /api/v1/policies/{id}/status`
- Any HTTP client on the network can trigger plan generation, create incidents, and approve recovery actions.

#### C. Method Security & Domain Authorization
- Zero `@PreAuthorize`, `@Secured`, or `RolesAllowed` annotations exist.
- Approval permission is not distinguished from standard API access.
- There is no concept of `ROLE_OPERATOR`, `ROLE_SRE_ADMIN`, or `ROLE_SERVICE_ACCOUNT`.

---

### 7. Operator Identity and Audit Identity

#### A. Approval Identity Capture
- Endpoint signature:
  `POST /api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve`
- The endpoint takes **no request body** and **no authentication principal**.
- In `RecoveryServiceImpl.approveRecoveryAction`:
  `action.setStatus(RecoveryActionStatus.APPROVED);`
  `action.setResult("Approved by human operator for execution");`
- The operator identity is completely anonymous and hardcoded.

#### B. Missing Audit Attributes
To support compliant, accountable actuation in Phase 5, the schema and DTO must capture:
- `approved_by_user_id`: UUID / String
- `approved_by_username`: String
- `approved_by_email`: String
- `approved_by_role`: String
- `approved_at`: Instant
- `approval_reason`: String
- `approval_token_hash`: String (cryptographic signature)
- `client_ip`: String
- `correlation_id`: String (from `CorrelationIdFilter`)

---

### 8. Transaction Boundaries

#### A. Existing Boundaries
- In `RecoveryServiceImpl`:
  - `generateRecoveryPlan(UUID incidentId)`: `@Transactional`
  - `approveRecoveryAction(UUID incidentId, UUID actionId)`: `@Transactional`
  - `createRecoveryPlan(...)`: `@Transactional`
- All database operations in `approveRecoveryAction` participate in a single local ACID transaction.

#### B. Danger of External Actuation in Transactions
If a future developer places actuator calls directly inside `approveRecoveryAction`:
```java
// DANGEROUS ANTI-PATTERN:
@Transactional
public RecoveryActionResponse approveAndExecute(...) {
    action.setStatus(RUNNING);
    recoveryActionRepository.save(action);
    kubernetesClient.restartPod(...); // EXTERNAL SIDE EFFECT!
    action.setStatus(SUCCESS);
    recoveryActionRepository.save(action);
}
```
**Failure Modes**:
1. **Ghost Mutation on Rollback**: If `restartPod(...)` succeeds, but the database connection drops before commit, the transaction rolls back. The database records `PENDING`, but the pod was restarted in the cluster!
2. **Connection Starvation**: External network calls hold PostgreSQL Hikari connections open during slow cluster mutations, exhausting the connection pool (`maximum-pool-size: 10`).

#### C. Missing Outbox / Event Infrastructure
- AURORA has no `TransactionalOutboxEntity`, no outbox table, and no durable message queue.
- Phase 5 must decouple database state updates from actuation dispatch using an Outbox pattern or transactional event listener (`@TransactionalEventListener(phase = AFTER_COMMIT)`).

---

### 9. Post-Action Telemetry and Verification

#### A. Incident Resolution Today
- `IncidentController` exposes:
  `PATCH /api/v1/incidents/{id}/status?status=RESOLVED`
- Handled by `IncidentServiceImpl.updateIncidentStatus(...)`.
- Updates `resolvedAt = Instant.now()` and saves.
- **Zero metric verification is performed**. Resolution is 100% manual.

#### B. Concept of Verification Loop
- **Before State**: The anomaly evaluation score and observed value at incident detection.
- **Action State**: The recovery action proposed and approved.
- **After State**: Post-action telemetry.
- **Verification Engine**: None exists.
- **Phase 5 Requirement**: A dedicated `VerificationService` that:
  1. Records baseline metric value $M_{\text{baseline}}$ at $T_{\text{action}}$.
  2. Enters verification window $[T_{\text{action}} + \Delta_{\text{warmup}}, T_{\text{action}} + \Delta_{\text{timeout}}]$.
  3. Queries `TelemetryService` for observations.
  4. Compares observed values against policy thresholds and baseline.
  5. Flags action as `VERIFIED_SUCCESS` (metric normalized), `VERIFIED_INEFFECTIVE` (metric unchanged), or `VERIFIED_REGRESSION` (metric degraded, trigger rollback).

---

### 10. Retry and Timeout Infrastructure

#### A. Current Infrastructure
- No Spring Retry (`@Retryable`).
- No Resilience4j (`CircuitBreaker`, `RateLimiter`, `Retry`, `Bulkhead`).
- No `@EnableAsync` or `@Async` execution pools.
- No `@EnableScheduling` or `@Scheduled` jobs.

#### B. Destructive Actuator Retry Hazard
- If a naive retry loop is applied to an actuator without idempotency:
  - `RESTART_POD`: Pod killed repeatedly, causing crash loops.
  - `SCALE_OUT`: Multiplied replica count, exhausting cluster quota.
  - `ROLLBACK`: Rolled back multiple versions into untested legacy deployments.
- Phase 5 requires strict separation between **read-only verification retries** (safe with exponential backoff) and **mutating action retries** (strictly prohibited without deterministic idempotency tokens).

---

### 11. Failure Handling and Incident Escalation

#### A. Failure Representation Today
- An inconclusive RCA, policy block, or anti-flapping cooldown is represented as:
  - `actionType: "MANUAL_INVESTIGATION"`
  - `status: PENDING`
  - `result: "<explicit deterministic reason>"`
  - `risk: RecoveryRisk.CRITICAL`
  - `approvalRequired: true`
- Plan reasoning appends `[POLICY BLOCKED: ...]` or `[ANTI-FLAPPING COOLDOWN: ...]`.

#### B. Anti-Loop Guarantees
- `RecoveryServiceImpl.generateRecoveryPlan(UUID incidentId)` checks:
  ```java
  Optional<RecoveryPlanEntity> existingPlan = recoveryPlanRepository.findByIncidentId(incidentId);
  if (existingPlan.isPresent()) {
      return mapToResponse(existingPlan.get(), existingActions);
  }
  ```
- **Strict Idempotency**: An incident can have at most one recovery plan. Repeated calls return the existing plan without generating duplicate actions.
- **Cooldown Isolation**: 2 failures in 1 hour triggers suppression to `MANUAL_INVESTIGATION`, preventing infinite restart loops.

#### C. Notification & Alerting Gaps
- AURORA has no notification subsystem (no webhooks, PagerDuty, Slack, SMTP).
- Failures and escalations are stored silently in the database.

---

### 12. Architecture Rules (ArchUnit)

#### A. Existing Guardrails
Enforced by `com.aurora.platform.architecture.ArchitectureRulesTest` (25 rules passing):
- **Rule A & M**: Controllers cannot expose `@Entity` classes.
- **Rule B**: Controllers cannot depend on Repositories.
- **Rule C**: DTOs cannot depend on Repositories.
- **Rule D & E**: Detectors cannot depend on controllers or persistence entities.
- **Rule F**: Recovery execution classes must not exist (`RecoveryExecutor`, `AutonomousRemediation`, `KubernetesActuator`, `ShellExecutor`, `AutoRollback`).
- **Rule G**: RCA cannot depend on recovery.
- **Rule K**: Common infrastructure cannot depend on domain packages.
- **Rule Q1–Q8**: LLM narrative domain purity and provider adapter isolation.

#### B. Proposed Architecture Rules for Phase 5
When Phase 5 is designed, Rule F must be replaced by structured architectural boundaries:
1. **Rule F1 (Actuator Interface Purity)**:
   `noClasses().that().resideInAPackage("..recovery.domain..").should().dependOnClassesThat().resideInAnyPackage("..kubernetes..", "..amazonaws..", "..google.cloud..", "..azure..")`
2. **Rule F2 (Actuator Implementation Isolation)**:
   `classes().that().implement(RecoveryActuatorPort.class).should().resideInAPackage("..recovery.infrastructure.actuator..")`
3. **Rule F3 (Zero Shell Execution)**:
   `noClasses().should().callMethod(Runtime.class, "exec").orShould().callConstructor(ProcessBuilder.class)`
4. **Rule F4 (Authorization Gate Mandatory)**:
   `noClasses().that().resideInAPackage("..recovery.infrastructure.actuator..").should().beAccessed().byClassesThat().resideOutsideOfPackage("..recovery.application.guard..")`

---

### 13. Safe Mock / Sandbox Actuator Testing

#### Conceptual Test Architecture
In Phase 5, testing must never touch live infrastructure or require external cloud credentials.

```
+-------------------------------------------------------------+
|                     Test Harness Context                    |
|                                                             |
|   +-----------------------+      +----------------------+   |
|   |  RecoveryTestHarness  | ---> |  MockRecoveryActuator|   |
|   +-----------------------+      +----------------------+   |
|                                             |               |
|                                  +----------------------+   |
|                                  | In-Memory Environment|   |
|                                  | PodState: RUNNING    |   |
|                                  | DBState: SATURATED   |   |
|                                  +----------------------+   |
|                                             |               |
|   +-----------------------+                 v               |
|   | SimulatedTelemetrySvc | <--- Telemetry emitted post-act |
|   +-----------------------+                                 |
+-------------------------------------------------------------+
```

1. **`MockRecoveryActuator`**: Implements `RecoveryActuatorPort`. Records invocation history, validates authorization tokens, and mutates in-memory target state.
2. **Deterministic Scenarios**:
   - `Scenario A (Clean Recovery)`: Pod restart -> state changes to `HEALTHY` -> telemetry drops below threshold -> verification passes.
   - `Scenario B (Timeout)`: Actuator delays execution beyond configured timeout -> marks action `EXECUTION_TIMED_OUT`.
   - `Scenario C (Flapping Suppression)`: Actuator fails twice -> triggers anti-flapping -> proposal suppressed.
   - `Scenario D (Dry-Run)`: Logs intent without mutating in-memory target state.

---

### 14. Database / Schema Readiness

The table below maps required actuation capabilities against Flyway migrations V1–V6:

| Capability | Existing Representation | Status | Missing Schema Attributes | Likely Phase 5 Requirement |
| :--- | :--- | :---: | :--- | :--- |
| **Recovery Plan** | `recovery_plans` (V1) | READY | Target environment | Add `environment VARCHAR(50)` |
| **Recovery Action** | `recovery_actions` (V1) | NEEDS_EXTENSION | Target UUID, attempt count | Add `target_resource_id UUID`, `idempotency_key VARCHAR(100)` |
| **Operator Approval** | Result string text (V1) | MISSING | Approver identity, timestamp, signature | Add `approved_by VARCHAR(100)`, `approved_at TIMESTAMP`, `approval_token VARCHAR(255)` |
| **Execution Lifecycle** | `started_at`, `completed_at` | NEEDS_EXTENSION | Fine-grained status enum | Add check constraint or enum for `RUNNING`, `TIMED_OUT` |
| **Execution Audit** | None | MISSING | Independent attempt table | Create table `recovery_action_executions` (1-to-N with action) |
| **Verification State** | None | MISSING | Verification status, observed value, notes | Add `verification_status VARCHAR(50)`, `verification_observed_value DOUBLE`, `verified_at TIMESTAMP` |
| **Idempotency** | None | MISSING | Unique constraint on active execution | Add `idempotency_key VARCHAR(100) UNIQUE` |
| **Correlation Tracking**| MDC in memory | MISSING | Audit column linking trace ID | Add `correlation_id VARCHAR(100)` |
| **Outbox Dispatch** | None | MISSING | Outbox table for atomic event dispatch | Create table `recovery_outbox_events` |

---

### 15. Human Authorization / Safety Boundary

#### A. The Safety Boundary Today
```
Incident Detected
       ↓
Deterministic RCA Analysis
       ↓
Recovery Plan Synthesized (Status: PENDING)
       ↓
Anti-Flapping Check: [PASS]
       ↓
Pre-Flight Policy Check: [PASS]
       ↓
Human Approval Endpoint (`POST .../approve`)
       ↓
Status Updated to `APPROVED`
       ↓
[HARD ARCHITECTURAL STOP — RULE F]
       ↓
ZERO ACTUATION ENGINE EXISTS. NO FURTHER CODE RUNS.
```

#### B. Search for Prohibited Concepts
A source-wide audit confirms:
- `Runtime.getRuntime().exec`: **0 occurrences**
- `ProcessBuilder`: **0 occurrences**
- `kubectl` / `io.kubernetes`: **0 occurrences**
- `com.amazonaws` / `software.amazon`: **0 occurrences**
- `com.google.cloud`: **0 occurrences**
- `com.azure`: **0 occurrences**
- `RecoveryExecutor`: **0 occurrences in main code** (ArchUnit rule only)
- `AutonomousRemediation`: **0 occurrences in main code** (ArchUnit rule only)
- `KubernetesActuator`: **0 occurrences in main code** (ArchUnit rule only)
- `ShellExecutor`: **0 occurrences in main code** (ArchUnit rule only)
- `AutoRollback`: **0 occurrences in main code** (ArchUnit rule only)

#### C. What is Currently Impossible?
1. Autonomous execution of recovery actions is **physically impossible** in the codebase.
2. Silent background execution is **physically impossible**.
3. Re-execution loops are blocked by anti-flapping suppression and idempotent plan caching.

#### D. What Must Remain Impossible Until Explicit Human Authorization?
1. No action may ever transition from `PENDING` to `APPROVED` without verified operator identity.
2. In `production`, no automated proposal may execute without a second-party human approval or explicit break-glass token.
3. No actuation adapter may receive unvalidated or freeform command strings.

---

### Cross-Cutting Analysis

#### A. Current Architecture Map

```
+-----------------------------------------------------------------------------------+
|                                 AURORA INGESTION                                  |
|  POST /api/v1/telemetry                                                           |
|          │                                                                        |
|          ▼                                                                        |
|  TelemetryController ────► TelemetryService ────► TelemetryEventRepository (DB)   |
+----------------──────────────────┬────────────────────────────────----------------+
                                   │
                                   ▼
+-----------------------------------------------------------------------------------+
|                               STATISTICAL ANOMALY                                 |
|  AnomalyDetectionService ───► AnomalyDetector (Z-Score / MAD)                     |
+----------------------------------┬────────────────────────────────----------------+
                                   │ (status == ANOMALOUS)
                                   ▼
+-----------------------------------------------------------------------------------+
|                               INCIDENT CORRELATION                                |
|  IncidentCorrelationService ───► IncidentRepository & EvidenceRepository (DB)     |
+----------------------------------┬────────────────────────────────----------------+
                                   │
                                   ▼
+-----------------------------------------------------------------------------------+
|                           DETERMINISTIC RCA ENGINE                                |
|  RcaAnalysisService ───► ResourceDependencyRepository (Graph Topology)            |
|                     ───► EdgeWeightProvider (Bayesian Shrinkage)                  |
|                     ───► RcaAnalysisRepository & CandidateRepository (DB)         |
+----------------------------------┬────────────────────────────────----------------+
                                   │
                                   ▼
+-----------------------------------------------------------------------------------+
|                             RECOVERY PLANNING DOMAIN                              |
|  RecoveryController (POST /recovery-plan)                                         |
|          │                                                                        |
|          ▼                                                                        |
|  RecoveryService.generateRecoveryPlan                                             |
|          │                                                                        |
|          ├──► PolicyService.getActivePolicies (Operational Rules)                 |
|          ├──► RecoveryActionRepository.findByTargetAndActionType (Cooldown)       |
|          └──► RecoveryPlanRepository & RecoveryActionRepository (Save PENDING)    |
+----------------------------------┬────────────────────────────────----------------+
                                   │
                                   ▼
+-----------------------------------------------------------------------------------+
|                              HUMAN APPROVAL GATE                                  |
|  RecoveryController (POST .../actions/{id}/approve)                               |
|          │                                                                        |
|          ▼                                                                        |
|  RecoveryService.approveRecoveryAction                                            |
|          │                                                                        |
|          ├──► Verify Status == PENDING                                            |
|          ├──► Verify Cooldown Invariant                                           |
|          ├──► Verify Policy Guardrails                                            |
|          └──► Set Status = APPROVED (Saved to DB)                                 |
+----------------------------------┬────────────────────────────────----------------+
                                   │
                                   ▼
                 =======================================
                 [HARD SAFETY STOP — RULE F PRESERVED]
                  ZERO ACTUATORS / ZERO EXECUTION LOGIC
                 =======================================
```

---

#### B. Phase 5 Readiness Matrix

| Architectural Area | Current State | Readiness Status | Evidence | Phase 5 Implication |
| :--- | :--- | :---: | :--- | :--- |
| **Telemetry Ingestion** | Synchronous REST -> PostgreSQL | `READY` | `TelemetryServiceImpl.java` | Can support ingestion during execution |
| **Incident & RCA Core** | Deterministic diagnostic pipeline | `READY` | `RcaAnalysisServiceImpl.java` | Clean diagnosis provided to recovery |
| **Plan Synthesis** | Metric-driven deterministic proposal | `READY` | `RecoveryServiceImpl.java` | Actions synthesized reliably |
| **Policy Pre-Flight** | Guardrails and threshold blocks | `READY` | `evaluatePolicyPreFlight` | Violations converted to manual triage |
| **Anti-Flapping** | 1-hour window $\ge 2$ failure tracking | `READY` | `evaluateAntiFlapping` | Prevents cascading flapping |
| **Target Isolation** | String name matching | `NEEDS_EXTENSION` | `RecoveryActionEntity.target` | Must bind to `targetResourceId` & environment |
| **Action State Machine** | 6 enum values, 1 active transition | `NEEDS_EXTENSION` | `RecoveryActionStatus.java` | Needs execution & verification states |
| **Post-Action Telemetry** | No time-bounded query | `NEEDS_EXTENSION` | `TelemetryService.java` | Must expose query from `completedAt` |
| **Database Schema** | V1–V6 schema | `NEEDS_EXTENSION` | `db/migration/V1..V6` | Needs approver, attempt, verification columns |
| **Authentication & AuthZ**| Completely open endpoints | `MISSING` | `pom.xml` (no Spring Security) | **BLOCKER**: Must secure approve endpoint |
| **Operator Audit Identity**| Anonymous hardcoded string | `MISSING` | `approveRecoveryAction` | **BLOCKER**: Must record authenticated user |
| **Verification Engine** | None | `MISSING` | No verification classes | Must build convergence verification engine |
| **Outbox / Dispatcher** | Local `@Transactional` only | `MISSING` | No outbox table | Must decouple DB commit from actuation |
| **Retry & Timeout Engine**| None | `MISSING` | No Resilience4j / retry | Must enforce idempotent timeout boundaries |
| **Execution Authority** | Rule F strictly enforced | `MUST_REMAIN_GUARDED`| `ArchitectureRulesTest.java` | Must remain impossible until gates pass |

---

#### C. Actuation Boundary Proposal

```
[ Domain Layer ]
      │
      ▼
RecoveryActionApprovedEvent
      │
      ▼
[ Application Layer: Guard Gate ]
      ├── 1. Verify Signed Operator Identity & Role
      ├── 2. Verify Environment Constraints (Disallow prod if dry-run)
      ├── 3. Verify Active Cooldown & Policy Invariants
      ├── 4. Generate Idempotency Execution Lease Token
      │
      ▼
RecoveryActuatorPort (Domain Interface)
      ▲
      │ (implements)
[ Infrastructure Layer: Adapters ]
      ├── KubernetesPodRestartAdapter
      ├── DatabasePoolResetAdapter
      ├── CloudScaleOutAdapter
      └── SimulatedSandboxActuator (for tests)
```
- **Hexagonal Invariant**: Concrete Kubernetes or cloud SDK classes must live exclusively in `com.aurora.platform.recovery.infrastructure.actuator.*`.
- The domain layer (`com.aurora.platform.recovery.*`) must depend solely on `RecoveryActuatorPort`.

---

#### D. Idempotency Analysis

| Operation | Current Idempotency Mechanism | Gap for Phase 5 Execution |
| :--- | :--- | :--- |
| **Plan Generation** | Returns existing plan if present for incident | None. Fully idempotent. |
| **Action Approval** | Returns existing action if status is `APPROVED` | Does not record multi-operator concurrent approval. |
| **Action Execution** | None (does not execute) | **CRITICAL GAP**: Without an execution lease token or lock version, two worker threads could pick up the same `APPROVED` action and invoke external mutations twice. |

---

#### E. Failure Model

For Phase 5, an action lifecycle must distinguish the following failure states:
1. `PRE_FLIGHT_BLOCKED`: Suppressed by policy or cooldown before proposal.
2. `APPROVAL_REJECTED`: Rejected by human operator.
3. `DISPATCH_FAILED`: Unable to reach external control plane (e.g. Kube API 503).
4. `EXECUTION_TIMED_OUT`: Actuation initiated but external system did not confirm within SLA.
5. `EXECUTION_FAILED`: External system rejected mutation (e.g. Kube API 403 or container crash).
6. `VERIFICATION_TIMED_OUT`: Metric failed to report post-recovery observations within window.
7. `VERIFIED_REGRESSION`: Metric worsened post-action; triggered automated rollback.
8. `VERIFIED_INEFFECTIVE`: Metric remained anomalous post-action; escalated to SRE.
9. `VERIFIED_SUCCESS`: Metric normalized below threshold; marked resolved.

---

#### F. Security / Safety Threats

| Threat | Existing Protection | Gap | Required Future Control |
| :--- | :--- | :--- | :--- |
| **Approval Bypass** | Rule F prevents execution | Unauthenticated REST endpoint | Spring Security JWT/mTLS + Role checks |
| **Target Collision** | Isolated by resource name | Duplicate names across environments collide in cooldown | Scope target by `UUID` and `environment` |
| **Ghost Mutation** | No actuators exist | External side effects inside `@Transactional` | Transactional Outbox pattern |
| **Duplicate Execution** | No execution | No distributed lock or idempotency token | Database lease token / optimistic lock |
| **Actuator Command Injection** | No actuators exist | Future developers using raw strings | Strongly typed enum parameters only; ban shell |
| **Flapping / Cascading Loops** | Anti-flapping cooldown ($\ge 2$ in 1h) | Alerting not sent on suppression | Notification dispatch to PagerDuty/Slack |

---

#### G. Phase 5 Implementation Boundary

#### 1. MUST IMPLEMENT BEFORE ACTUATION (Pre-requisites)
- Add Spring Security with authentication, operator principal resolution, and role-based access control.
- Persist operator audit identity (`approved_by`, `approved_at`, `approval_reason`) on recovery actions.
- Bind `RecoveryActionEntity` to `target_resource_id` (UUID) and `target_environment`.
- Create a Transactional Outbox table (`recovery_outbox_events`) to decouple DB commit from actuation.
- Implement an explicit `RecoveryActuatorPort` domain interface.

#### 2. SAFE TO IMPLEMENT IN PHASE 5
- `SimulatedSandboxActuator` for deterministic offline testing.
- `KubernetesActuator` adapter using strongly-typed API clients (no raw shell/exec).
- Post-action verification service querying post-action telemetry window.
- Actuation metrics (`recovery_action_duration_seconds`, `recovery_action_failures_total`).

#### 3. SHOULD NOT BE IMPLEMENTED IN PHASE 5
- Full autonomous auto-approval in `production` (ADR-005 strictly mandates human approval).
- Cross-cloud multi-region orchestration engines.
- Machine-learning driven execution triggers.

#### 4. EXPLICITLY FORBIDDEN FOR NOW (Violates Safety Contract)
- Any shell command executor (`Runtime.getRuntime().exec()`, `ProcessBuilder`, `/bin/sh`).
- Autonomous remediation without human approval gates.
- Actuation dispatch inside synchronous controller threads.
- Direct actuator invocation without pre-flight policy evaluation and anti-flapping checks.

---

### Reconnaissance Audit Trail

1. **Files Inspected**:
   - `platform/aurora-control-plane/pom.xml`
   - `platform/aurora-control-plane/src/main/resources/application.yml`
   - `platform/aurora-control-plane/src/main/resources/application-test.yml`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/service/RecoveryServiceImpl.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/entity/RecoveryActionEntity.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/entity/RecoveryPlanEntity.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/entity/RecoveryActionStatus.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/repository/RecoveryActionRepository.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/service/TelemetryServiceImpl.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/repository/TelemetryEventRepository.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/service/IncidentServiceImpl.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/service/IncidentCorrelationServiceImpl.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/service/RcaAnalysisServiceImpl.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/resource/entity/ResourceEntity.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/policy/service/PolicyServiceImpl.java`
   - `platform/aurora-control-plane/src/main/java/com/aurora/platform/common/web/CorrelationIdFilter.java`
   - `platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java`
2. **Database Migrations Inspected**: `V1__init_control_plane_schema.sql` through `V6__incident_narratives.sql`.
3. **Tests Inspected**: Full Maven test suite (`524 total`, `516 passed`, `0 failures`, `0 errors`, `8 skipped`).
4. **Exact Commit Checked**: `e5750ffd15b0934bb18c8d7503aa4ca27fc7e23d`.
5. **Recommended Next Architectural Decisions**:
   - Issue **ADR-008: Controlled Actuation Architecture, Security Pre-conditions & Verification Lifecycle** before writing any Phase 5 code.
   - Introduce Spring Security and operator audit identity models as the mandatory Step 1 of Phase 5.
