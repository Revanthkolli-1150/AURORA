# Phase 5A Technical Design: Controlled Actuation Foundation

---

## 1. Executive Summary

Phase 5A establishes the architectural, security, lifecycle, and verification foundations for controlled recovery actuation in the AURORA reliability platform. 

In Phases 1 through 4B, AURORA developed a deterministic reliability pipeline:
- Ingesting metrics and detecting anomalies ([`AnomalyDetectionService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/service/AnomalyDetectionService.java))
- Correlating incidents and building topological dependency graphs ([`IncidentCorrelationService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/incident/service/IncidentCorrelationService.java), [`ResourceDependencyRepository`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/resource/repository/ResourceDependencyRepository.java))
- Diagnosing root causes via empirical graph weights and neighborhood evidence ([`RcaAnalysisService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/intelligence/rca/service/RcaAnalysisService.java))
- Synthesizing recovery plans guarded by pre-flight operational policies and anti-flapping suppression ([`RecoveryService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/service/RecoveryService.java))

Under **Rule F**, AURORA intentionally possessed zero execution authority. Phase 5A designs the foundation required to bridge proposal to actuation safely, without yet implementing concrete cloud or container actuators.

### Critical Invariants of Phase 5A Design:
1. **Separation of Concerns**: Business intent (`RecoveryAction`), physical invocation (`ExecutionAttempt`), and observational convergence (`Verification`) are modeled as three separate domain concepts rather than a monolithic state machine.
2. **Fail-Closed Security**: Unauthenticated, unverified, expired, or ambiguous requests are strictly denied.
3. **Transactional Outbox Decoupling**: Database state changes are decoupled from external network side effects to eliminate ghost mutations and connection pool starvation. The outbox is the **durability boundary**.
4. **Authoritative Canonical Target Identity**: Target identity is anchored strictly in the immutable `target_resource_id` (UUID), preventing cross-environment naming collisions.
5. **Zero Production Side Effects in Phase 5A**: Phase 5A provides only the interface contracts, domain state machines, and a deterministic in-memory `SimulatedSandboxActuator`. Real actuators (Kubernetes, AWS, GCP) are deferred to Phase 5B.

---

## 2. Current State (Repository Baseline)

The current baseline is commit `e5750ffd15b0934bb18c8d7503aa4ca27fc7e23d` (`feat: implement phase 4 policy-guarded recovery planning`):

- **Repository**: `platform/aurora-control-plane`
- **Branch**: `main` (Synchronized with `origin/main`)
- **Test Suite**: 524 total tests (516 passed, 0 failures, 0 errors, 8 skipped due to host Docker socket access for Testcontainers)
- **Architecture Guardrails**: 25/25 ArchUnit tests passing in [`ArchitectureRulesTest.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java).

### Current Codebase Anatomy & Limitations:
1. **Approval Endpoint**: [`RecoveryController.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/controller/RecoveryController.java) exposes `POST /api/v1/incidents/{incidentId}/recovery-plan/actions/{actionId}/approve`. It accepts no request body, no security principal, and performs no authentication.
2. **Approval Logic**: [`RecoveryServiceImpl.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/service/RecoveryServiceImpl.java) validates that the action is `PENDING`, verifies that the action is not in anti-flapping cooldown, checks that active policies allow the action, sets status to `APPROVED`, and writes result `"Approved by human operator for execution"`. It then stops.
3. **Target Identity**: [`RecoveryActionEntity.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/recovery/entity/RecoveryActionEntity.java) stores target as a loose `String target`, populated with `resource.name()`. Because `resources.name` is non-unique across environments ([`ResourceEntity.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/resource/entity/ResourceEntity.java)), cooldown and anti-flapping queries collide between `staging` and `production`.
4. **Execution History**: `RecoveryActionEntity` only has `startedAt` and `completedAt`. Repeated execution attempts or retries overwrite historical timestamps, destroying audit history.
5. **Telemetry Querying**: [`TelemetryServiceImpl.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/service/TelemetryServiceImpl.java) exposes only `getTelemetryByResourceId(UUID, String)`, which returns all historical events. It cannot query telemetry starting from an action's completion timestamp.
6. **Rule F Preservation**: Zero instances of `RecoveryExecutor`, `AutonomousRemediation`, `KubernetesActuator`, `ShellExecutor`, `AutoRollback`, `Runtime.exec`, or `ProcessBuilder` exist in the repository.

---

## 3. Explicit Safety Invariants

The following seventeen safety invariants define the non-negotiable boundaries of controlled actuation in AURORA:

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

## 4. Target Architecture

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

### Architectural Distinctions:
- **Approval $\ne$ Execution**: Approving a plan records authorization in the database; it does not invoke external infrastructure.
- **Outbox Durability $\ne$ Notification**: The transactional outbox in PostgreSQL provides the guaranteed durability boundary. Memory-based event notifications (e.g., Spring `AFTER_COMMIT`) are optional latency optimizations, not durability guarantees.

---

## 5. Proposed Domain Model

Phase 5A establishes a strict separation of concerns across three distinct entities:

```
+--------------------------------------------------------------------+
|                         RECOVERY DOMAIN                            |
|                                                                    |
|   +--------------------+ 1      * +--------------------+           |
|   |    RecoveryPlan    |──────────|   RecoveryAction   |           |
|   +--------------------+          +---------┬----------+           |
|                                             │ 1                    |
|                                             │                      |
|                                             │ *                    |
|                                   +---------▼----------+ 1       1 |
|                                   |  ExecutionAttempt  |───────────+
|                                   +--------------------+           |
|                                                                    │
|                                                           +--------▼---------+
|                                                           |   Verification   |
|                                                           +------------------+
+--------------------------------------------------------------------+
```

### Responsibility Boundaries:
- **`RecoveryAction`**: *"What AURORA decided should be done."* Represents business intent, proposed parameters, policy pre-flight results, risk, and approval state.
- **`ExecutionAttempt`**: *"What was actually requested, dispatched, and executed."* Represents a single physical invocation of an actuator with idempotency tokens, lease management, and raw response payloads.
- **`Verification`**: *"What telemetry evidence says happened afterward."* Represents observational evidence, baseline comparisons, and metric convergence.

### Cardinality Specifications:
- `RecoveryAction` (1) $\longleftrightarrow$ (0..N) `ExecutionAttempt`: An approved action may have zero attempts (awaiting execution), one successful attempt, or multiple sequential attempts if earlier attempts timed out or failed.
- `ExecutionAttempt` (1) $\longleftrightarrow$ (0..1) `Verification`: Only a successfully executed attempt (`status == SUCCEEDED`) transitions to a verification lifecycle. Failed attempts do not enter verification.

---

## 6. Target Safety & Canonical Resource Identity

### Canonical Identity: Target Resource UUID
`target_resource_id` (UUID referencing `resources.id`) is the **authoritative, canonical identity** of the resource:
$$\text{target\_resource\_id} \longrightarrow \text{ResourceEntity} \longrightarrow \text{canonical environment, type, name}$$

- **Snapshot Context Attributes**: `target_environment`, `target_resource_type`, and `target_resource_name` on `RecoveryActionEntity` are read-only snapshot attributes captured at plan creation time for audit and UI display. They **must not** independently redefine or override the resource identity.
- **Validation Invariant**: A recovery action cannot represent `resource ID = A, environment = production` if `ResourceEntity A` belongs to `staging`. At plan creation, these attributes are populated strictly from the resolved `ResourceEntity`.
- **Target Immutability (S17)**: Once a `RecoveryAction` is approved, its `target_resource_id` is **strictly immutable**. Subsequent resource renames or environment tag modifications in the inventory cannot silently redirect the approved action. If the target resource is deleted or unavailable at execution time, execution fails safely closed.
- **Anti-Flapping Scoping**: Cooldown checks are queried by `(target_resource_id, action_type)`. Because UUID is globally unique, staging failures can never collide with or lock down production resources.

---

## 7. Execution State Models

### 1. `RecoveryAction` Intent State Machine
```
              [ Proposed ]
                   │
                   ▼
              ( PENDING ) ──────────────────────────┐
                   │                                │ (Operator rejects)
                   ▼ (Operator approves)            ▼
              ( APPROVED )                    ( REJECTED ) [Terminal]
                   │                                ▲
                   ├──► ( CANCELLED ) [Terminal]    │ (Superceded by newer plan)
                   │                                │
                   ▼ (Execution Attempt Requested)  └── ( SUPERSEDED ) [Terminal]
             ( IN_PROGRESS )
                   │
         +---------+---------+
         │                   │
         ▼                   ▼
   ( COMPLETED )         ( FAILED )
   [Terminal]            [Terminal]
   (Attempt succeeded &  (Attempts exhausted or
    verified healthy)     verification regressed)
```

### 2. `ExecutionAttempt` Invocation State Machine
```
         ( REQUESTED )
               │ (Outbox writes command)
               ▼
        ( DISPATCHED ) ─────────────────────────────┐
               │ (Actuator acknowledges start)      │ (Network/Outbox error)
               ▼                                    ▼
        ( EXECUTING ) ───────────────┐         ( FAILED ) [Terminal]
               │                     │              ▲
               ▼ (Actuator success)  ▼ (Timeout)    │ (Actuator error)
        ( SUCCEEDED )          ( TIMED_OUT ) ───────┘
        [Triggers              [Terminal]
         Verification]
```

### 3. `Verification` Observational State Machine
```
        ( SCHEDULED )
               │ (Warmup delay elapsed)
               ▼
        ( OBSERVING ) ──────────────────────────────┐
               │                                    │ (Telemetry stops arriving)
               ├──────────────────────┐             ▼
               ▼ (Metric normalized)  ▼ (Degraded) ( TIMED_OUT ) [Terminal]
      ( VERIFIED_HEALTHY )  ( VERIFIED_DEGRADED )
      [Action -> COMPLETED] [Action -> FAILED; Alert SRE]
               │
               ▼ (High variance / conflicting signals)
      ( VERIFIED_INCONCLUSIVE )
      [Action -> FAILED; Alert SRE]
```

---

## 8. Persistence Design (Future Flyway Migration V7 Blueprint)

*(No migration scripts are executed in Phase 5A).*

```
+---------------------------------------------------------------------------------------------+
|                                    FLYWAY V7 SCHEMA DESIGN                                  |
|                                                                                             |
|   recovery_plans (V1)                                                                       |
|         │ 1                                                                                 |
|         ▼ *                                                                                 |
|   recovery_actions                                                                          |
|         │ 1                                                                                 |
|         ├───────────────────────────────────────────────────────+                           |
|         ▼ *                                                     ▼ *                         |
|   execution_attempts                                      recovery_outbox_events            |
|         │ 1                                               (AggregateId, Payload, Status)    |
|         ▼ 1                                                                                 |
|   verifications                                                                             |
|   (AttemptId, BaselineValue, ObservedSamples, Status)                                       |
+---------------------------------------------------------------------------------------------+
```

### Proposed Schema Extensions:
1. **`recovery_actions`**:
   - `target_resource_id UUID NOT NULL REFERENCES resources(id)`
   - `target_environment VARCHAR(50) NOT NULL` (Snapshot)
   - `target_resource_type VARCHAR(50) NOT NULL` (Snapshot)
   - `target_resource_name VARCHAR(255) NOT NULL` (Snapshot)
   - `approved_by_user_id VARCHAR(100)`
   - `approved_by_email VARCHAR(255)`
   - `approved_by_capability VARCHAR(50)`
   - `approved_at TIMESTAMP WITH TIME ZONE`
   - `approval_reason TEXT`
   - `version BIGINT NOT NULL DEFAULT 0`
   - `INDEX idx_recovery_actions_target (target_resource_id, action_type)`
2. **`execution_attempts`** (New Table):
   - `id UUID PRIMARY KEY`
   - `recovery_action_id UUID NOT NULL REFERENCES recovery_actions(id) ON DELETE CASCADE`
   - `attempt_number INT NOT NULL`
   - `idempotency_key VARCHAR(64) NOT NULL UNIQUE`
   - `lease_token UUID`
   - `lease_expires_at TIMESTAMP WITH TIME ZONE`
   - `status VARCHAR(50) NOT NULL`
   - `external_execution_ref VARCHAR(255)`
   - `dispatched_at TIMESTAMP WITH TIME ZONE NOT NULL`
   - `completed_at TIMESTAMP WITH TIME ZONE`
   - `raw_response_payload TEXT`
   - `failure_reason TEXT`
   - `version BIGINT NOT NULL DEFAULT 0`
3. **`verifications`** (New Table):
   - `id UUID PRIMARY KEY`
   - `execution_attempt_id UUID NOT NULL UNIQUE REFERENCES execution_attempts(id) ON DELETE CASCADE`
   - `target_resource_id UUID NOT NULL REFERENCES resources(id)`
   - `metric_name VARCHAR(255) NOT NULL`
   - `baseline_value DOUBLE PRECISION NOT NULL`
   - `policy_threshold DOUBLE PRECISION NOT NULL`
   - `status VARCHAR(50) NOT NULL`
   - `observation_started_at TIMESTAMP WITH TIME ZONE NOT NULL`
   - `observation_ended_at TIMESTAMP WITH TIME ZONE`
   - `observed_samples_count INT NOT NULL DEFAULT 0`
   - `final_observed_value DOUBLE PRECISION`
   - `verification_notes TEXT`
4. **`recovery_outbox_events`** (New Table):
   - `id UUID PRIMARY KEY`
   - `aggregate_type VARCHAR(100) NOT NULL`
   - `aggregate_id UUID NOT NULL`
   - `event_type VARCHAR(100) NOT NULL`
   - `idempotency_key VARCHAR(64) NOT NULL UNIQUE`
   - `payload TEXT NOT NULL`
   - `status VARCHAR(50) NOT NULL` (`PENDING`, `PROCESSING`, `SENT`, `DEAD_LETTER`)
   - `retry_count INT NOT NULL DEFAULT 0`
   - `created_at TIMESTAMP WITH TIME ZONE NOT NULL`

---

## 9. Security & Operator Authorization Model

### Authentication & Authorization Flow
$$\text{AUTHENTICATED OPERATOR} \longrightarrow \text{EXPLICIT AUTHORIZATION} \longrightarrow \text{AUDITABLE IDENTITY} \longrightarrow \text{APPROVAL}$$

### Security Capabilities (Vendor-Neutral)
1. **Viewer / Read-Only Capability**: Inspect telemetry, incidents, and recovery plans.
2. **Recovery Approval Capability (Non-Prod)**: Authorize execution in `development`, `staging`, and `test`.
3. **Production Approval Capability**: Authorize execution in `production`.
4. **Administrative / Break-Glass Capability**: Update policies and execute exceptional authorized workflows.

### Auditable Operator Approval Audit
Mandatory audit fields captured at approval:
- `approved_by_user_id`: Unique identifier of the authenticated operator.
- `approved_by_email`: Operator contact email.
- `approved_by_capability`: Capability verified at approval time.
- `approved_at`: UTC timestamp.
- `approval_reason`: Justification text.
- `incident_id`, `recovery_action_id`, `correlation_id`.

*(Optional future extension: Cryptographic digital signature, signing certificate, or external ticket reference).*

---

## 10. Transaction Boundary & Outbox Dispatcher

### Durability Boundary
The relational database transaction is the **durability boundary**.
- When an operator approves an action or requests execution, updates and outbox events commit together in a single ACID transaction.
- **NO EXTERNAL ACTUATION OCCURS INSIDE THIS TRANSACTION.**
- If the application crashes, the outbox record is persisted on disk in PostgreSQL and recovered upon restart.

> **[OPEN DECISION: Outbox Dispatcher Implementation]**  
> - *Option A*: Scheduled Polling Worker using PostgreSQL `SELECT ... FOR UPDATE SKIP LOCKED`.
> - *Option B*: Hybrid model combining Spring `@TransactionalEventListener(phase = AFTER_COMMIT)` notification wakeup with scheduled polling fallback.

---

## 11. Actuator Port Contract

```java
package com.aurora.platform.recovery.application.port.out;

public interface RecoveryActuatorPort {

    ActuationResult execute(ActuationRequest request);

    ActuationStatusResult queryStatus(String externalExecutionReference);
}
```

- **Vendor-Neutral Payloads**: `ActuationRequest` and `ActuationResult` operate solely on domain concepts (`ResourceType`, action string, parameter map, target UUID, timeout).
- **Prohibitions**:
  - `Runtime.getRuntime().exec` and `ProcessBuilder` are **strictly prohibited** across the platform.
  - Zero Kubernetes or cloud SDK classes in domain or application packages.

---

## 12. Post-Action Verification Architecture

### Telemetry Query Extension Requirement
[`TelemetryService`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/main/java/com/aurora/platform/telemetry/service/TelemetryService.java) must be extended with:
```java
List<TelemetryEventResponse> getTelemetrySince(UUID resourceId, String metricName, Instant since);
List<TelemetryEventResponse> getTelemetryInWindow(UUID resourceId, String metricName, Instant start, Instant end);
```

### Verification Criteria
Exact numerical convergence thresholds are **action- and metric-specific**, configured via policy or parameters, and are **NOT universal architectural constants**:

> **[EXAMPLE — NOT AN ARCHITECTURAL REQUIREMENT]**  
> - Pod restart for CPU saturation: Requires 3 samples below 80% threshold across 120 seconds.
> - Cache flush for query latency: Requires latency $<50\text{ms}$ within 30 seconds.

### Invariant Outcomes:
- **`VERIFIED_HEALTHY`**: Telemetry confirms metric returned to and stabilized within policy thresholds.
- **`VERIFIED_DEGRADED`**: Telemetry worsened significantly relative to baseline; triggers SRE escalation.
- **`VERIFIED_INCONCLUSIVE`**: Data is insufficient, stale, or conflicting; fails closed (does not auto-resolve).
- **`TIMED_OUT`**: Telemetry stopped reporting during the observation window.

---

## 13. Configuration Model

Configuration in `application.yml` is **fail-closed** by default:

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

## 14. Architecture Tests (ArchUnit Rules for Phase 5A)

When Phase 5A is implemented, Rule F will evolve into the following permanent ArchUnit rules in [`ArchitectureRulesTest.java`](file:///c:/Users/user/OneDrive/Desktop/AURORA/platform/aurora-control-plane/src/test/java/com/aurora/platform/architecture/ArchitectureRulesTest.java):

```java
@Test
@DisplayName("Rule F1: Actuator port isolation - domain and application never import external infrastructure clients")
void actuatorPortIsolation() {
    noClasses()
        .that().resideInAnyPackage("..recovery.domain..", "..recovery.application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "io.kubernetes..", "com.amazonaws..", "com.google.cloud..", "com.azure.."
        ).check(importedClasses);
}

@Test
@DisplayName("Rule F2: Actuator implementations must reside exclusively in infrastructure actuator package")
void actuatorImplementationsInInfrastructure() {
    classes()
        .that().implement(RecoveryActuatorPort.class)
        .should().resideInAPackage("..recovery.infrastructure.actuator..")
        .check(importedClasses);
}

@Test
@DisplayName("Rule F3: Absolute prohibition of Runtime.exec and ProcessBuilder across the platform")
void absoluteProhibitionOfProcessExecution() {
    noClasses()
        .should().callMethod(Runtime.class, "exec")
        .orShould().callConstructor(ProcessBuilder.class)
        .check(importedClasses);
}

@Test
@DisplayName("Rule F4: Actuators can only be accessed through the execution orchestrator guard gate")
void actuatorAccessRestrictedToOrchestrator() {
    noClasses()
        .that().resideInAPackage("..recovery.infrastructure.actuator..")
        .should().beAccessed().byClassesThat().resideOutsideOfPackage("..recovery.application.orchestration..")
        .check(importedClasses);
}
```

---

## 15. Classification of Decisions & Open Choices

| Decision Topic | Classification | Architectural Stance / Open Choice |
| :--- | :---: | :--- |
| **Separation of Concerns** | `DECIDED` | Decoupled into `RecoveryAction`, `ExecutionAttempt`, and `Verification`. |
| **Outbox Durability Boundary** | `DECIDED` | Transactional Outbox is durability boundary; no actuation in DB tx. |
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

## 16. Explicit Phase 5A vs. Phase 5B Boundary

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

---

## 17. Acceptance Criteria for Phase 5A Implementation

Phase 5A will be deemed complete and verified when all of the following criteria are met:

1. **Authentication Requirement**: Approval requests require an authenticated operator identity.
2. **Capability Authorization**: Authorization capabilities are evaluated before action approval.
3. **Explicit Production Authorization**: In `production`, execution cannot occur without explicit human authorization from an authorized operator.
4. **Canonical & Immutable Target Identity**: Target identity is anchored strictly in `target_resource_id` (UUID), resolved at plan creation, and immutable thereafter.
5. **No Target Collision**: Target environment cannot conflict with `ResourceEntity.environment`, preventing staging/production collision.
6. **Mandatory Policy & Anti-Flapping**: Pre-flight policy evaluation and anti-flapping checks remain mandatory gates.
7. **Approval Isolation**: Approval does not directly invoke an actuator.
8. **Transactional Decoupling**: External actuation occurs strictly outside the database transaction.
9. **Durable Outbox Handoff**: The transactional outbox provides durable execution handoff resilient to process crashes.
10. **Idempotency Guarantee**: Submitting duplicate execution requests with the same idempotency key cannot produce duplicate external actions.
11. **Three-Lifecycle Separation**: `RecoveryAction`, `ExecutionAttempt`, and `Verification` remain separate entities with separate lifecycles.
12. **Truthful Verification**: Verification failure or inconclusive telemetry cannot silently become `VERIFIED_HEALTHY`.
13. **Vendor-Neutral Actuator Port**: `RecoveryActuatorPort` contains zero vendor SDK types.
14. **Process Execution Ban**: Zero usage of `Runtime.exec` or `ProcessBuilder`.
15. **Rule F Preserved**: Rule F remains enforced until controlled execution is explicitly introduced.
16. **Sandbox Simulation**: `SimulatedSandboxActuator` can substitute for future real actuators in tests.
17. **Real Actuation Deferred**: Zero real production actuation capability exists in Phase 5A.
