# ADR-008: Controlled Actuation Architecture, Security Preconditions & Verification Lifecycle

## Status
Proposed

## Date
2026-09-29

---

## 1. Context

AURORA has completed Phase 4B as verified in commit `e5750ffd15b0934bb18c8d7503aa4ca27fc7e23d` (`feat: implement phase 4 policy-guarded recovery planning`). At this baseline:
- Deterministic recovery plans are synthesized from Root Cause Analysis (RCA) primary candidates.
- Actions undergo pre-flight policy evaluation against active operational guardrails ([`PolicyService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/policy/service/PolicyService.java)).
- Actions enforce anti-flapping suppression (suppressing actions if $\ge 2$ failures occur within a 1-hour rolling window for a given target and action type).
- Human operators can approve recovery actions via `POST /api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve`.
- **Absolute Rule F Safety Boundary**: Recovery execution classes are strictly prohibited. In [`ArchitectureRulesTest.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java), Rule F confirms zero presence of `RecoveryExecutor`, `AutonomousRemediation`, `KubernetesActuator`, `ShellExecutor`, or `AutoRollback`. Approval transitions an action from `PENDING` to `APPROVED` in the relational database, records a static text result, and **stops completely**.

### Key Findings from Phase 5 Architecture Reconnaissance

A comprehensive architecture reconnaissance of the entire codebase ([`PHASE_5_ARCHITECTURE_RECONNAISSANCE.md`](file:///c:/Users/user/OneDrive/Desktop/AURORA/PHASE_5_ARCHITECTURE_RECONNAISSANCE.md)) identified critical gaps and risks that must be resolved prior to introducing any execution capability:

1. **Rule F & Current Lifecycle Gap**:
   In [`RecoveryServiceImpl.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/service/RecoveryServiceImpl.java), the only enforced state transition in production code is `PENDING -> APPROVED`. While [`RecoveryActionStatus`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/entity/RecoveryActionStatus.java) contains `RUNNING`, `SUCCESS`, `FAILED`, and `ROLLED_BACK`, there are no methods, transitions, or entities representing actual execution or verification.
2. **Lack of Authenticated Operator Identity**:
   [`pom.xml`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/pom.xml) does not include `spring-boot-starter-security`. All endpoints—including action approval—are completely unauthenticated HTTP endpoints. Approval currently sets:
   ```java
   action.setResult("Approved by human operator for execution");
   ```
   No operator ID, username, email, role, or authorization context is accepted, verified, or persisted.
3. **String-Based Target Identity & Cross-Environment Collisions**:
   [`RecoveryActionEntity`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/entity/RecoveryActionEntity.java) stores target as a loose string (`@Column(name = "target") private String target`), populated with `resource.name()` (e.g., `"order-service"`). In [`V1__init_control_plane_schema.sql`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/resources/db/migration/V1__init_control_plane_schema.sql), `resources.name` is indexed but **not unique**. Two resources can share a name across environments (e.g., `order-service` in `staging` vs `production`). Consequently:
   `recoveryActionRepository.findByTargetAndActionType("order-service", ...)` collides across environments, causing a staging failure to inappropriately lock down production via anti-flapping cooldown.
4. **Absence of Execution Attempts**:
   `RecoveryActionEntity` represents a single action record. If execution fails and a retry or separate attempt is initiated, existing timestamps (`startedAt`, `completedAt`) and results are overwritten, destroying the immutable audit trail.
5. **Absence of Post-Action Verification**:
   Incident resolution today is 100% manual via `PATCH /api/v1/incidents/{id}/status?status=RESOLVED`. There is no concept of a verification loop ("Observe $\to$ Act $\to$ Observe Again"), baseline metric capture, or regression detection.
6. **Absence of Idempotent Execution Protection**:
   `RecoveryActionEntity` lacks an `idempotency_key`, execution lease token, and optimistic lock version. Two concurrent worker threads could attempt to execute the same approved action simultaneously.
7. **Transaction-Boundary Risk**:
   `RecoveryServiceImpl.approveRecoveryAction` is annotated with `@Transactional`. If external actuation (e.g., restarting a pod or scaling a replica set) is invoked synchronously within this transaction, two catastrophic failure modes arise:
   - **Ghost Mutation**: The external mutation succeeds in the cluster, but the database connection fails or rolls back before commit. The database reports `PENDING`, but the external resource was mutated.
   - **Connection Pool Starvation**: Slow network calls to external cluster APIs hold PostgreSQL Hikari connections open, exhausting the connection pool (`maximum-pool-size: 10`).
8. **Absence of a Transactional Outbox**:
   There is no outbox table or durable event dispatcher to decouple local ACID database commits from asynchronous external actuation.
9. **Telemetry Query Limitations**:
   In [`TelemetryServiceImpl.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/service/TelemetryServiceImpl.java), `TelemetryService` only exposes `getTelemetryByResourceId(UUID resourceId, String metricName)`, returning all events chronologically from inception. It does not provide a time-bounded query API (e.g., `since(Instant timestamp)` or `between(Instant start, Instant end)`) necessary for evaluating post-action telemetry.
10. **Absence of Actuator Implementations**:
    No actuators, Kubernetes clients, cloud SDKs, or shell execution utilities exist.

This ADR defines the architecture for **Phase 5A: Controlled Actuation Foundation**. Phase 5A establishes the domain models, security contracts, lifecycle separation, outbox architecture, and actuator port boundaries, **without implementing real external actuators or autonomous execution authority**.

---

## 2. Decision Drivers

1. **Human Authorization**: Every mutating action in production must require auditable human operator authorization.
2. **Fail-Closed Behavior**: Any unauthenticated, unverified, expired, or ambiguous request must fail closed (deny execution).
3. **Production Safety**: Zero autonomous mutation in production; explicit human authorization mandatory.
4. **Policy Enforcement & Anti-Flapping**: Actuation must strictly preserve Phase 4 pre-flight policy evaluation and anti-flapping suppression.
5. **Target Isolation**: Targets must be uniquely identified by canonical, immutable UUIDs to prevent cross-environment collisions.
6. **Idempotency**: Execution requests must be uniquely identified by idempotency keys to eliminate duplicate mutations.
7. **Transaction Consistency**: Database state transitions must never share an ACID transaction with external network mutations (outbox pattern as durability boundary).
8. **Observability & Auditability**: Every attempt, approval, execution, and verification must produce an immutable audit log.
9. **Post-Action Verification**: Every action must be verified against post-action telemetry to confirm metric normalization or detect regressions.
10. **Vendor Neutrality**: Actuator interfaces must be pure domain ports; no cloud or Kubernetes SDKs in domain or application packages.
11. **Testability**: The system must support complete offline simulation via sandbox/mock actuators.
12. **Architectural Enforcement**: Strict ArchUnit rules must prevent regression to unverified or direct execution.

---

## 3. Explicit Safety Invariants

The following seventeen safety invariants define the non-negotiable boundaries of controlled actuation in AURORA. They must be preserved across all future implementations and verified via automated architecture and integration tests:

- **S1 (Authentication Gate)**: No unauthenticated actor can approve a recovery action.
- **S2 (Approval Isolation)**: Approval never directly performs external actuation.
- **S3 (Execution Prerequisite)**: No execution request may exist without a persisted, approved recovery action.
- **S4 (Policy Non-Bypass)**: Policy enforcement cannot be bypassed by any execution path.
- **S5 (Cooldown Non-Bypass)**: Anti-flapping cooldown cannot be bypassed by any execution path.
- **S6 (Attempt Idempotency)**: Every execution attempt has a unique identity and an execution idempotency key.
- **S7 (Canonical Target Binding)**: Execution target is bound to a canonical, immutable resource identity (`target_resource_id`).
- **S8 (Production Human Authorization)**: Production execution requires explicit human authorization from an authorized operator.
- **S9 (Transactional Decoupling)**: External actuation occurs strictly outside the database transaction.
- **S10 (Observable Terminal Result)**: Every execution attempt has an observable terminal result (`SUCCEEDED`, `FAILED`, or `TIMED_OUT`).
- **S11 (Mandatory Verification Lifecycle)**: Every completed execution enters a verification lifecycle unless an explicitly documented safety rule prevents verification.
- **S12 (Truthful Verification)**: Failure to obtain verification evidence cannot silently become `VERIFIED_HEALTHY` / `VERIFIED_SUCCESS`.
- **S13 (Process Execution Ban)**: No actuator may execute arbitrary shell commands or launch host processes (`Runtime.exec`, `ProcessBuilder`).
- **S14 (Vendor Neutrality)**: Domain and application layers remain vendor-neutral with zero external cloud or container SDK dependencies.
- **S15 (Rule F Continuity)**: Rule F remains enforced until controlled execution is explicitly introduced and separately authorized.
- **S16 (Duplicate Execution Prevention)**: A duplicate execution request must not result in an unsafe duplicate external action.
- **S17 (Target Immutability)**: Execution must use the target identity captured by the approved action and cannot silently switch targets.

---

## 4. Architectural Decision

### High-Level Component Flow

```
Incident
   │
   ▼
RCA Engine
   │
   ▼
Recovery Plan Synthesis
   │
   ▼
Policy Pre-Flight & Anti-Flapping Checks
   │
   ▼
RecoveryAction Created (Status: PENDING)
   │
   ▼
Authenticated Human Approval
   │
   ▼
Transactional DB Commit
   ├──────────────────────────────┐
   ▼                              ▼
RecoveryAction: APPROVED       Outbox Event: PENDING
                                  │
                                  ▼ (Durable Asynchronous Dispatch)
                               Outbox Dispatcher
                                  │
                                  ▼
                               Execution Gate
                                  │
                                  ▼
                         RecoveryActuatorPort
                                  │
                                  ▼
                           ExecutionAttempt
                                  │
                                  ▼
                             Verification
                                  │
                                  ▼
                           Telemetry Query
```

### Architectural Layer Responsibilities
1. **Domain Layer**:
   - `RecoveryAction`: Bounded aggregate representing the planned remedy, its risk, policy compliance, canonical target reference, and business lifecycle (`PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `SUPERSEDED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`).
   - `ExecutionAttempt`: Entity capturing an individual physical or simulated invocation attempt, including idempotency key, lease token, dispatched timestamp, raw response, and status (`REQUESTED`, `DISPATCHED`, `EXECUTING`, `SUCCEEDED`, `FAILED`, `TIMED_OUT`).
   - `Verification`: Entity capturing the post-action verification contract, baseline metric value, observation window, evaluated post-action telemetry, and convergence outcome (`SCHEDULED`, `OBSERVING`, `VERIFIED_HEALTHY`, `VERIFIED_DEGRADED`, `VERIFIED_INCONCLUSIVE`, `TIMED_OUT`).
2. **Application Layer**:
   - `RecoveryApprovalGuard`: Enforces operator authentication, role/capability verification, policy re-validation, and cooldown invariants before recording approval.
   - `ExecutionOrchestrator`: Validates execution prerequisites, generates idempotency tokens, manages execution leases, writes outbox events, and routes attempts.
   - `VerificationService`: Queries post-action telemetry across the observation window and evaluates convergence against baseline metrics.
3. **Infrastructure Boundary**:
   - `RecoveryActuatorPort`: Outbound domain port defining the vendor-neutral execution contract.
4. **Infrastructure Layer**:
   - Outbox event listener / dispatcher.
   - Actuator adapters implementing `RecoveryActuatorPort`. In Phase 5A, this is strictly a `SimulatedSandboxActuator`. Real infrastructure adapters (Kubernetes, Cloud) are deferred to Phase 5B.

---

## 5. Security Decision

### 1. Architectural Requirement: Authenticated, Authorized Operator
The architecture mandates a strict authorization pipeline:
$$\text{AUTHENTICATED OPERATOR} \longrightarrow \text{EXPLICIT AUTHORIZATION} \longrightarrow \text{AUDITABLE IDENTITY} \longrightarrow \text{APPROVAL}$$

- **Fail-Closed Default**: Any request lacking an authenticated operator principal must fail closed with immediate access denial.
- **Decoupling from Specific IdP**:
  > **[OPEN DECISION: Identity Provider & Token Protocol]**  
  > The repository currently possesses zero security dependencies. The exact authentication protocol is an **open implementation decision**. Plausible mechanisms include:
  > - *Option A*: Spring Security OAuth2/OIDC Resource Server decoding JWT Bearer tokens.
  > - *Option B*: Reverse proxy / API Gateway terminating authentication and injecting trusted mTLS headers (e.g., `X-Aurora-Operator-Id`).
  > - *Option C*: Internal cryptographic token validation filter.  
  > Phase 5A requires only that an authenticated principal with verifiable identity attributes is available in the application context.

### 2. Authorization Capabilities (Rather than Hardcoded Roles)
Rather than prescribing rigid, framework-specific role strings, the architecture defines four distinct **security capabilities**:
1. **Viewer / Read-Only Capability**: Inspect resources, telemetry, incidents, RCA diagnoses, and proposed recovery plans.
2. **Recovery Approval Capability (Non-Production)**: Authorize recovery action execution in non-production environments (`development`, `staging`, `test`).
3. **Production Approval Capability**: Authorize recovery action execution in `production`.
4. **Administrative / Break-Glass Capability**: Perform policy updates and emergency authorizations.

### 3. Production Approval Governance
> **[OPEN DECISION: Single vs. Dual Human Approval in Production]**  
> While the architecture strictly enforces that **production execution cannot occur without explicit human authorization from an authorized operator**, the exact governance model is an open decision:
> - *Option A*: Single appropriately authorized human operator holding production approval capability.
> - *Option B*: Two-person approval / quorum ("Two-Man Rule") requiring two distinct operator authorizations before action execution is unlocked.
> - *Option C*: Organizationally mandated approval ticket reference (e.g., ServiceNow/Jira change ticket validation).  
> The Phase 5A domain model supports multiple approval entries per action to allow either option without structural redesign.

### 4. Auditable Operator Approval Audit
Approval records must capture traceable operator identity attributes:
- **Mandatory Attributes**:
  - `approved_by_user_id`: Unique identifier of the authenticated operator.
  - `approved_by_email`: Operator contact email.
  - `approved_by_capability`: Authorization capability verified at approval.
  - `approved_at`: UTC timestamp of approval.
  - `approval_reason`: Justification text provided by the operator (mandatory in production).
  - `incident_id`, `recovery_action_id`, `correlation_id`.
- **Optional Future Extensions**:
  - Cryptographic digital signature, signing certificate, or external change management approval reference. *(These are not required for Phase 5A foundation).*

### 5. Break-Glass Policy Semantics
Break-glass does **NOT** mean "bypass safety rules and execute arbitrarily." If implemented, break-glass is an exceptional, audited authorization path:
- Operator must possess Administrative / Break-Glass Capability.
- Mandatory, detailed justification text (minimum character length enforced).
- Triggers immediate high-priority audit alerts.
- Does not bypass target immutability, transactional decoupling, or idempotency invariants.

---

## 6. Target Identity Decision

### Canonical Identity: Target Resource UUID
`target_resource_id` (UUID referencing `resources.id`) is the **sole canonical identity** of the recovery target:
$$\text{target\_resource\_id} \longrightarrow \text{ResourceEntity} \longrightarrow \text{canonical environment, type, name}$$

- **Snapshot Attributes**: `target_environment`, `target_resource_type`, and `target_resource_name` on recovery records are read-only snapshot attributes for display and audit logging. They **must not** independently redefine or override the resource identity.
- **Validation Invariant**: A recovery action cannot represent `resource ID = A, environment = production` if `ResourceEntity A` belongs to `staging`. Target references must be resolved from `ResourceEntity` at plan creation time and validated for consistency.
- **Target Immutability**: Once a `RecoveryAction` is approved, its `target_resource_id` is **strictly immutable**. Subsequent resource renames or environment tag changes in the inventory cannot silently redirect the approved action. If the target resource is deleted or unavailable at execution time, execution fails safely closed.
- **Anti-Flapping Identity**: Cooldown tracking must be queried by `(target_resource_id, action_type)`. Because UUID is globally unique, staging failures can never collide with or lock down production resources.

---

## 7. Lifecycle Decision: Separation of Concerns

### Rejection of Monolithic State Machine
We explicitly reject collapsing approval, execution, and verification into a single giant enum. Doing so conflates business intent with physical execution attempts and observational evidence.

Instead, we establish three distinct lifecycles with explicit cardinalities:
- `RecoveryAction` (1) $\longleftrightarrow$ (0..N) `ExecutionAttempt`
- `ExecutionAttempt` (1) $\longleftrightarrow$ (0..1) `Verification`  
  *(A failed execution attempt does not enter verification; a successful execution produces exactly one verification lifecycle).*

```
[ RECOVERY ACTION: Intent Lifecycle ]
  "What Aurora decided should be done."
  PENDING ──► APPROVED ──► [Attempts Orchestrated] ──► COMPLETED (Verified Healthy)
     │            │                                       │
     ├──► REJECTED├──► CANCELLED                          └──► FAILED (Attempts exhausted
     │            │                                                    or regression)
     └──► SUPERSEDED

[ EXECUTION ATTEMPT: Invocation Lifecycle ]
  "What was actually requested/dispatched/executed."
  REQUESTED ──► DISPATCHED ──► EXECUTING ──► SUCCEEDED (Triggers Verification)
                    │               │
                    ├──► FAILED     ├──► FAILED
                    │               │
                    └──► TIMED_OUT  └──► TIMED_OUT

[ VERIFICATION: Observational Lifecycle ]
  "What telemetry evidence says happened afterward."
  SCHEDULED ──► OBSERVING ──► VERIFIED_HEALTHY (RecoveryAction -> COMPLETED)
                    │
                    ├──► VERIFIED_DEGRADED (RecoveryAction -> FAILED; Alert SRE)
                    ├──► VERIFIED_INCONCLUSIVE (RecoveryAction -> FAILED; Alert SRE)
                    └──► TIMED_OUT (Telemetry ceased; Alert SRE)
```

---

## 8. Transaction Boundary & Outbox Durability Decision

### Durability vs. Latency Notification
- **Durability Boundary: Transactional Outbox**: The relational database transaction is the boundary of durability. When an action is approved or an execution is requested, the update and an outbox event are committed together atomically.
- **Notification / Latency Optimization**: Mechanisms such as Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` or async event publishers are optional latency optimizations to wake up dispatchers immediately. They **must not** be relied upon as the durability guarantee. If the JVM crashes immediately after commit, the outbox record persists in PostgreSQL and will be picked up upon restart.
- **Critical Invariant**: **NO EXTERNAL ACTUATION OCCURS INSIDE THE APPROVAL DATABASE TRANSACTION.**

```
HTTP Approval Request
        │
        ▼
[ Local ACID Transaction ]
        ├─► Persist RecoveryAction (APPROVED)
        ├─► Persist Operator Approval Audit Record
        └─► Persist RecoveryOutboxEvent (PENDING)
        │
     COMMIT
        │
        ▼ (Transaction Committed to Disk)
Outbox Dispatcher (Durable Polling or Notification Wakeup)
        │
        ▼
Execution Gate (Validates Leases & Pre-conditions)
        │
        ▼
RecoveryActuatorPort (External Network Invocation)
        │
        ▼
ExecutionAttempt Recorded
        │
        ▼
Verification Engine Triggered
```

> **[OPEN DECISION: Outbox Dispatcher Implementation]**  
> - *Option A*: Scheduled Polling Worker using PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED` (Guarantees safety in clustered deployments; zero distributed lock dependencies).
> - *Option B*: Hybrid model combining `AFTER_COMMIT` event dispatch with a scheduled polling fallback to catch missed events.

---

## 9. Idempotency and Concurrency

Execution idempotency belongs to execution semantics, not merely action approval:
1. **Execution Idempotency Key**: Every execution request requires a unique `idempotency_key` (UUID). The outbox and attempt tables enforce a `UNIQUE` constraint on this key.
2. **Optimistic Locking**: `RecoveryActionEntity` and `ExecutionAttemptEntity` maintain `@Version private Long version` columns.
3. **Execution Lease**: Before an attempt is dispatched, the orchestrator acquires an execution lease with a finite TTL (e.g., 60 seconds). No duplicate attempt can be initiated while an active lease is held.
4. **Target Resource Isolation**: The execution gate ensures that no two mutating actions can execute concurrently against the same `target_resource_id`.

---

## 10. Actuator Boundary & Vendor Neutrality

### Interface Contract: `RecoveryActuatorPort`
Located in `com.aurora.platform.recovery.application.port.out`:
```java
public interface RecoveryActuatorPort {
    ActuationResult execute(ActuationRequest request);
    ActuationStatusResult queryStatus(String externalExecutionReference);
}
```

- **Vendor-Neutral Payloads**: `ActuationRequest` and `ActuationResult` operate solely on domain concepts (`ResourceType`, action string, parameter map, target UUID, timeout).
- **Prohibitions**:
  - `Runtime.getRuntime().exec` and `ProcessBuilder` are **strictly prohibited** across the platform.
  - Zero Kubernetes or cloud SDK classes may appear in domain or application packages.
  - Real adapters are **out of scope** for Phase 5A.

---

## 11. Verification Decision

### Post-Action Verification Model
Recovery is an empirical hypothesis that must be confirmed by telemetry:
1. **Baseline Capture**: The primary candidate's metric value at execution time is recorded as $M_{\text{baseline}}$.
2. **Verification Window**: Verification begins after an action-specific warmup delay ($\Delta_{\text{warmup}}$) and observes telemetry across a defined window ($\Delta_{\text{window}}$).
3. **Telemetry Freshness Requirement**: Observed samples must have timestamps within a freshness threshold ($\Delta_{\text{freshness}}$) of the evaluation instant.
4. **Success / Regression Criteria**:
   > **[OPEN DECISION: Action- and Metric-Specific Verification Thresholds]**  
   > Exact numerical convergence criteria (e.g., sample counts, percentage improvements) must be configurable per action type, metric, or policy, and are **NOT universal architectural constants**:
   > - *EXAMPLE (NOT AN ARCHITECTURAL REQUIREMENT)*: A pod restart for high CPU might require 3 samples below 80% threshold, whereas a cache flush might require latency $<50\text{ms}$ within 30 seconds.
   > - *Regression Principle*: If post-action metrics worsen significantly relative to baseline, the verification engine marks the outcome `VERIFIED_DEGRADED` and alerts SREs.
   > - *Inconclusive Principle*: If samples are missing, stale, or highly volatile at window expiration, the outcome is marked `VERIFIED_INCONCLUSIVE` and fails closed. It cannot silently become healthy.
5. **Telemetry Query Requirement**: [`TelemetryService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/service/TelemetryService.java) must be extended with time-bounded query methods (`getTelemetrySince` / `getTelemetryInWindow`).

---

## 12. Retry and Timeout Semantics

1. **Mutating Actions**:
   - Actuator calls for mutating actions (`RESTART_POD`, `SCALE_OUT`, `ROLLBACK`) are **non-retryable by default**.
   - If an actuator invocation fails or times out, the attempt is marked `FAILED` or `TIMED_OUT`. An operator must review and authorize any subsequent attempt.
2. **Read-Only Verification**:
   - Verification telemetry queries are strictly read-only and **safely retryable** with exponential backoff across the observation window.

---

## 13. Environment and Configuration

Configuration must be fail-closed by default in `application.yml`:
```yaml
aurora:
  recovery:
    actuation:
      enabled: false                          # Master kill switch
      dry-run: true                            # Sandbox/simulation mode
      allowed-environments:                   # Allowed environments
        - development
        - test
      production-override-token: ""           # Required for production activation
      timeout-seconds: 60                      # Execution SLA
```

---

## 14. Architecture Rules Evolution (ArchUnit)

Rule F in [`ArchitectureRulesTest.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java) will evolve into four permanent architectural guardrails:

1. **Rule F1 (Actuator Port Purity)**: Domain and application packages must never import Kubernetes, AWS, GCP, or Azure SDKs.
2. **Rule F2 (Actuator Implementation Isolation)**: Actuator implementations must reside exclusively in infrastructure actuator packages.
3. **Rule F3 (Absolute Prohibition of Process / Shell Execution)**: Absolute ban on `Runtime.exec` and `ProcessBuilder`.
4. **Rule F4 (Mandatory Execution Guard Gate)**: Actuators can only be accessed through the execution orchestrator guard gate.

---

## 15. Consequences

### Positive Consequences
- **Elimination of Ghost Mutations**: Actuators are invoked outside local database transactions.
- **Accurate Auditability**: Every attempt, approval, and verification is independently recorded with operator identity.
- **Cross-Environment Safety**: Canonical UUID-based targeting stops staging events from locking out production resources.
- **Clean Extensibility**: Adding a new actuator in Phase 5B requires only implementing `RecoveryActuatorPort`.

### Negative Consequences
- **Schema Growth**: Introduces new tables (`execution_attempts`, `verifications`, `recovery_outbox_events`).
- **Operational Latency**: Outbox polling introduces sub-second dispatch latency compared to synchronous in-process calls.
- **Increased Test Complexity**: Requires testing outbox delivery, leases, timeouts, and verification convergence.

---

## 16. Classification of Architectural Decisions

| Decision Topic | Classification | Architectural Stance / Open Choice |
| :--- | :---: | :--- |
| **Separation of Concerns** | `DECIDED` | Decoupled into `RecoveryAction`, `ExecutionAttempt`, and `Verification`. |
| **Outbox Durability Boundary** | `DECIDED` | Transactional Outbox is the durability boundary; no actuation in DB tx. |
| **Target Canonical Identity** | `DECIDED` | `target_resource_id` is authoritative; snapshot attributes cannot conflict. |
| **Target Immutability** | `DECIDED` | Target immutable once approved; renames cannot redirect execution. |
| **Process Execution Ban** | `DECIDED` | `Runtime.exec` and `ProcessBuilder` strictly prohibited across platform. |
| **Human Authorization in Prod**| `DECIDED` | Production execution cannot occur without explicit human authorization. |
| **Identity Provider / Protocol** | `OPEN DECISION`| OAuth2/OIDC vs. Gateway mTLS headers vs. internal token validator. |
| **Single vs. Dual Production Approval**| `OPEN DECISION`| Single authorized operator vs. two-person approval quorum. |
| **Outbox Dispatch Mechanism** | `OPEN DECISION`| Polling worker (`SKIP LOCKED`) vs. hybrid `AFTER_COMMIT` with polling fallback. |
| **Verification Threshold Constants**| `OPEN DECISION`| Metric/action-specific thresholds configured via policy, not universal constants. |
| **Break-Glass Mechanics** | `OPEN DECISION`| Exceptional audited path requiring administrative capability. |

---

## 17. Explicit Phase 5A vs. Phase 5B Boundary

### Phase 5A — Foundation (Scope of this Design)
- Authentication and authorization foundation (capability-based).
- Traceable operator approval audit.
- Target UUID binding and immutability invariants.
- Three-lifecycle domain model (`RecoveryAction`, `ExecutionAttempt`, `Verification`).
- Execution idempotency and lease management.
- Transactional outbox engine and durable dispatcher contract.
- Vendor-neutral `RecoveryActuatorPort`.
- Post-action verification architecture and telemetry query contract.
- ArchUnit rules F1–F4 preserving Rule F spirit.
- In-memory `SimulatedSandboxActuator` for testing.

### Phase 5B — Real Actuation (Strictly Deferred)
- Concrete Kubernetes API actuator (`io.kubernetes` / Fabric8).
- Cloud provider actuators (AWS SDK, GCP client libraries, Azure SDK).
- Database connection pool actuators (HikariCP JMX/REST).
- Real external infrastructure side effects.
- Production cluster credentials and secret management.
- Autonomous self-healing loops without human authorization.
