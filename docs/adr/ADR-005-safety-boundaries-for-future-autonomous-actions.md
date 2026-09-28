# ADR-005: Safety Boundaries for Future Autonomous Actions

## Status
Accepted

## Date
2026-09-26

## Context
When an autonomous reliability system transitions from read-only observability and diagnosis (Phases 1 and 2) to active intervention (Phases 4 and 5), the system assumes active operational authority over production infrastructure.

Without explicit safety guardrails, autonomous actions can:
1. Trigger cascading failures through simultaneous or repeated restart loops.
2. Exhaust shared resources (e.g., overwhelming database connection pools or saturating disk I/O).
3. Introduce security vulnerabilities by executing unverified commands or violating least-privilege principles.
4. Violate compliance, change management, or data sovereignty policies.

## Decision
All future autonomous actions in AURORA must be strictly bounded by an **independently verifiable safety policy engine** before any actuator executes an action.

### Non-Negotiable Safety Principles:
1. **Blast Radius Limiting**:
   - Remediation actions must never target more than a defined percentage of fleet capacity at any given time (e.g., maximum 10% of cluster pods or 1 replica in an HA set).
2. **Rate Limiting & Anti-Flapping Cooldowns**:
   - Consecutive recovery actions on the same resource must enforce mandatory cooldown windows (e.g., minimum 15 minutes between container restarts).
   - If an action fails twice within 1 hour, autonomous remediation on that resource is permanently suspended, escalating immediately to human SREs.
3. **Pre-Flight Validation & Policy Enforcement**:
   - Every recovery plan must pass through the `policy` module, evaluating environment constraints (e.g., automated actions prohibited or strictly restricted in `PRODUCTION` without two-man approval).
4. **Deterministic Rollback & Verification**:
   - Every remediation action must define a measurable verification check (e.g., metric returning to healthy baseline within 120 seconds).
   - Failure of verification must trigger automatic rollback or safe termination of the action.
5. **Full Auditability**:
   - Every proposed action, authorization check, execution step, and verification outcome must be recorded in an immutable audit trail.

## Trade-offs
- **Consequences (Positive)**:
  - Guarantees that automated self-healing cannot destabilize production environments.
  - Ensures compliance with enterprise governance and security standards.
  - Protects against runaway AI or algorithmic loops.
- **Consequences (Negative)**:
  - Adds latency to recovery workflows as pre-flight checks and policy validations run.
  - Requires extensive configuration and tuning of blast radius and cooldown thresholds per environment.

## Future Triggers for Revisiting
- **Implementation of Phase 4 (Recovery Planning)**: When designing `recovery/` module models and APIs.
- **Implementation of Phase 5 (Autonomous Recovery)**: When building actuator integrations (Kubernetes operators, cloud APIs, SSH/Ansible connectors).
