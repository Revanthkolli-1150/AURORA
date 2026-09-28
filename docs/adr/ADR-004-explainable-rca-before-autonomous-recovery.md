# ADR-004: Explainable RCA Before Autonomous Recovery

## Status
Accepted

## Date
2026-09-26

## Context
In automated reliability engineering, the ultimate objective is often envisioned as full self-healing: automated remediation scripts, container restarts, traffic rerouting, or automated configuration rollbacks.

However, attempting automated recovery without transparent, explainable, and deterministic Root Cause Analysis (RCA) is dangerous. Executing automated remediations based on unverified correlations or black-box predictions leads to the classic SRE hazard: automated remediation loops, flapping services, and catastrophic cascading outages where the recovery system accelerates failure instead of mitigating it.

## Decision
AURORA strictly mandates that **explainable, deterministic Root Cause Analysis must precede any autonomous recovery actions**.

1. **Evidence-Based Causal Inference**:
   - RCA results must be composed of explicit, verifiable evidence items:
     - `ANOMALY` (statistical deviation on candidate metrics)
     - `TEMPORAL_PRECEDENCE` (anomaly occurred strictly prior to incident detection within the lookback window)
     - `DEPENDENCY` (topological relationship in the resource dependency graph)
     - `TELEMETRY_CORRELATION` (directional movement matching known degradation patterns)
   - Every candidate root cause must have a concrete, human-auditable evidence score and explanation trail.
2. **Deterministic Ranking**:
   - Candidates are ranked strictly by:
     1. Evidence Score DESC
     2. Temporal Delta ASC (candidates closer in time to the incident win ties)
     3. Candidate Resource ID ASC (deterministic tie-breaking)
3. **No Premature Autonomous Actuation**:
   - Phase 2B focuses solely on deterministic investigation.
   - Autonomous recovery planning (Phase 4) and autonomous recovery execution (Phase 5) will not be permitted until RCA explainability and confidence scoring are battle-tested and validated.

## Trade-offs
- **Consequences (Positive)**:
  - Eliminates the risk of automated "wrong healing" actions destroying healthy infrastructure.
  - SREs retain full visibility and trust in the system's reasoning.
  - Generates structured, reproducible incident diagnostic records that can later train historical intelligence engines (Phase 2C).
- **Consequences (Negative)**:
  - Human intervention remains necessary to execute remediations until Phase 4/5.
  - Time-to-mitigation is bounded by human response times rather than automated scripts in early phases.

## Future Triggers for Revisiting
- **Recovery Advisory System (Phase 4)**: When RCA accuracy exceeds operational confidence thresholds, begin offering non-destructive, human-in-the-loop recovery recommendations (read-only action plans).
- **Automated Actuation (Phase 5)**: When recovery plans are proven safe with comprehensive rollback mechanisms, allow safe autonomous execution within strict blast radius constraints.
