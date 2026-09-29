# Phase 4B — Pre-Flight Policy Evaluation, Anti-Flapping Cooldown & Manual Incidents

## 1. Overview & Purpose

Phase 4B enhances the deterministic recovery proposal scaffolding established in Phase 4A by introducing:
1. **Pre-Flight Policy Evaluation**: Integrating the existing `PolicyService` to evaluate operational guardrails, explicit prohibitions, and safety thresholds *before* any action is proposed as executable.
2. **Anti-Flapping Safety Cooldown**: Tracking historical recovery failures by resource and action identity, automatically suppressing repeated failing proposals (2 failures within a 1-hour window) and escalating directly to human SRE investigation.
3. **Manual Incident Creation**: Exposing `POST /api/v1/incidents` to permit manual detection of external anomalies or operational events into the AURORA reliability pipeline with standard Bean Validation and HTTP 201 Created semantics.
4. **Preservation of Rule F**: All recovery actions remain proposals. Zero autonomous execution engines, actuators, shell commands, or cloud API callers exist.

---

## 2. Part A — Pre-Flight Policy Evaluation

### Semantics & Workflow
During recovery plan generation (`RecoveryServiceImpl.generateRecoveryPlan(UUID incidentId)`):
1. **Target Identification**: Resolves the target resource ID and its `ResourceType` (`POD`, `CONTAINER`, `DATABASE`, `SERVICE`, etc.) from the primary RCA candidate.
2. **Active Policy Loading**: Invokes `PolicyService.getActivePolicies(ResourceType targetType)`.
   - Disabled policies (`enabled == false`) are ignored.
   - Only policies matching the candidate resource type are retrieved.
3. **Metric Applicability**: Matches active policies against the primary candidate's metric:
   - Matches if policy metric is wildcard (`*` or `all`), exact match, or substring match.
4. **Constraint Evaluation**:
   - **Explicit Prohibitions**: Policies with action `PROHIBIT_<ACTION>`, `DENY_<ACTION>`, `BLOCK_<ACTION>`, `NO_<ACTION>`, `MANUAL_ONLY`, `BLOCKED`, `DENIED`, or `PROHIBIT`.
     - If a threshold is defined, the action is blocked when `observedValue >= policy.threshold()`.
     - If no threshold is defined, the action is blocked unconditionally.
   - **Allowed Actions & Threshold Ceilings**: Policies specifying allowed actions (e.g. `action = "RESTART_POD"` or `action = "ALLOW_RESTART_POD"`) allow the proposal, provided `observedValue <= policy.threshold()` if a threshold ceiling is defined.
5. **Policy Violation Handling**:
   - If an action violates a policy, it is **never** proposed as an executable action (`status: PENDING` with automated action type).
   - Instead, the action is transformed into `actionType: "MANUAL_INVESTIGATION"`, `status: PENDING`, with its `result` containing the explicit, deterministic blocking reason:
     `"Blocked by policy '<policy_name>': action '<action_type>' is prohibited for resource type <type> on metric '<metric>'"`
   - The plan's `reasoning` explicitly logs: `"[POLICY BLOCKED: ...]"`
   - Operational risk is escalated to `RecoveryRisk.CRITICAL`.
   - Human approval is forced: `approvalRequired = true`.
6. **Missing Policy Behavior**:
   - If no applicable policy exists for that resource type or metric, AURORA preserves existing deterministic recovery behavior. No synthetic policy defaults are silently invented.
7. **Defense-in-Depth on Approval**:
   - In `approveRecoveryAction`, active policies are verified before approval. If an active policy prohibits the action, approval is rejected with `IllegalStateException`.

---

## 3. Part B — Anti-Flapping Cooldown

### Motivation
In distributed systems, repeated automated attempts to remediate an underlying systemic issue (e.g. bouncing a crashing pod or clearing connection pools repeatedly) can cause flapping, worsen downstream cascading failures, or mask deeper infrastructure faults. Phase 4B implements deterministic anti-flapping suppression.

### Exact Cooldown Semantics
1. **What Constitutes a Failed Recovery Action**:
   - An instance of `RecoveryActionEntity` with `status == RecoveryActionStatus.FAILED`.
   - The failure timestamp is resolved from `action.completedAt != null ? action.completedAt : (action.startedAt != null ? action.startedAt : action.recoveryPlan.createdAt)`.
2. **Identity for Cooldown Tracking**:
   - Tracked by composite identity `(target, actionType)` where `target` is the resource name/identifier (e.g. `"auth-service"`) and `actionType` is the recovery action (e.g. `"RESTART_POD"`).
   - Different resources do **not** share cooldown state.
   - Different action types on the same resource do **not** share cooldown state.
3. **1-Hour Time Window Semantics**:
   - Window: `[now - 1 hour, now]`.
   - Actions older than 1 hour are strictly excluded from the count.
4. **Trigger Threshold**:
   - Cooldown is triggered when `failureCount >= 2` within the 1-hour window.
   - 0 or 1 failure: Cooldown is NOT triggered.
   - $\ge 2$ failures: Cooldown triggers and proposal is suppressed.
5. **Successful Recovery / Reset Behavior**:
   - If a recovery action for the same `(target, actionType)` succeeded (`status == RecoveryActionStatus.SUCCESS`), its timestamp $T_{\text{success}}$ resets prior failures.
   - Only failures occurring strictly after $T_{\text{success}}$ are counted.
6. **Suppression Behavior & Representation**:
   - When in cooldown, the automated action is suppressed.
   - A replacement action is persisted:
     - `actionType`: `"MANUAL_INVESTIGATION"`
     - `target`: `targetName`
     - `status`: `RecoveryActionStatus.PENDING`
     - `result`: `"Suppressed by anti-flapping cooldown: <N> failed recovery attempts recorded within the last 1 hour on target '<target>' for action '<action>'. Escalated to human SRE investigation."`
   - Plan `risk`: `RecoveryRisk.CRITICAL`.
   - Plan `approvalRequired`: `true`.
   - Plan `reasoning` appends: `"[ANTI-FLAPPING COOLDOWN: ...]"`
7. **Expiration**:
   - When the 1-hour window elapses relative to the historical failures, the count drops below 2, and cooldown clears automatically without manual intervention.

### Persistence & Schema Analysis
Inspection of `recovery_actions` schema (`V1__init_control_plane_schema.sql`) confirmed:
- Columns `target` (`VARCHAR(255)`), `action_type` (`VARCHAR(100)`), `status` (`VARCHAR(50)`), `started_at` (`TIMESTAMP`), and `completed_at` (`TIMESTAMP`) already exist.
- Derived repository query `findByTargetAndActionType(String target, String actionType)` fully satisfies all cooldown evaluation requirements.
- No new columns or Flyway migrations were necessary.

---

## 4. Part C — Manual Incident Creation

### Endpoint
`POST /api/v1/incidents`

### Request & Response
- **Request Body**: `CreateIncidentRequest` (JSON)
  - `resourceId`: UUID, non-null, must reference existing resource.
  - `title`: String, non-blank, max 255 chars.
  - `description`: String, optional.
  - `severity`: `IncidentSeverity` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), non-null.
  - `status`: `IncidentStatus` (`DETECTED`, `INVESTIGATING`, `DIAGNOSED`, `RESOLVED`, `CLOSED`), non-null.
  - `confidence`: Double, optional.
  - `rootCause`: String, optional.
  - `detectedAt`: Instant, optional (defaults to `Instant.now()`).
- **Response**: HTTP 201 Created with `Location` header (`/api/v1/incidents/{id}`) and `IncidentResponse` body.
- **Errors**:
  - HTTP 400 Bad Request on validation failure (`VALIDATION_ERROR`).
  - HTTP 404 Not Found if `resourceId` does not exist (`RESOURCE_NOT_FOUND`).

---

## 5. Architectural Invariants & Safety

1. **Rule F (Zero Execution Authority)**:
   - Preserved. No executors, actuators, shell runners, or background workers exist.
   - Verified by `ArchitectureRulesTest.recoveryExecutionClassesDoNotExist()` (Rule F).
2. **Determinism**:
   - Cooldown and policy decisions are 100% deterministic mathematical and temporal evaluations.
   - Zero LLM involvement, zero probabilistic heuristics.
3. **Hexagonal Layering & Entity Protection**:
   - DTOs strictly decouple controllers from JPA entities.
   - Repositories are never exposed to controllers.

---

## 6. Known Limitations & Next Steps

1. **Approved Recovery Action Execution**:
   - As per Rule F and ADR-005, recovery actions remain proposals. Executing approved actions requires Phase 5 guarded actuators.
2. **Policy Model Complexity**:
   - `PolicyEntity` supports `(targetResourceType, metricName, threshold, action, enabled)`. Complex Boolean compound expressions (e.g. `AND`/`OR` over multiple metrics) are not supported by the current schema.
