# AURORA Deterministic Root Cause Analysis (Phase 2B)

## 1. Overview
The Deterministic Root Cause Analysis (RCA) engine (`com.aurora.platform.intelligence.rca`) evaluates incident evidence against topological relationships and temporal telemetry facts to identify the most probable root cause behind an infrastructure degradation.

RCA is fully deterministic, explainable, and reproducible: identical inputs produce identical candidate rankings and evidence trails without stochastic ML models or LLM hallucinations.

---

## 2. Investigation Scope: 1-Hop Topological Neighborhood

The RCA engine investigates candidates within the direct 1-hop neighborhood of the incident resource:
1. **The Incident Resource**: The resource on which the incident was detected.
2. **Direct Dependencies (Upstream)**: Resources that the incident resource directly depends on (`source = incidentResource`).
3. **Direct Dependents (Downstream)**: Resources that directly depend on the incident resource (`target = incidentResource`).

> [!NOTE]
> Canonical Phase 2B investigates **direct dependencies and direct dependents**. It does not perform multi-hop recursive graph traversal or breadth-first search across arbitrary graph depths. Unrelated resources outside this 1-hop radius are strictly excluded.

---

## 3. Evidence Evaluation & Scoring

Each candidate in the topological neighborhood is evaluated across four deterministic evidence types:

| Evidence Type | Weight | Evaluation Logic |
| :--- | :--- | :--- |
| `ANOMALY` | **0.40** | Awarded if the candidate exhibited an active statistical anomaly within the lookback window. |
| `TEMPORAL_PRECEDENCE` | **0.25** | Awarded if candidate anomaly occurred strictly prior to incident detection ($\Delta t > 0$) within the 10-minute lookback window ($0 < \Delta t \le 600\text{s}$). |
| `DEPENDENCY` | **0.20** | Awarded if the candidate is an upstream dependency of the incident resource (`incidentResource DEPENDS_ON candidate`). |
| `TELEMETRY_CORRELATION` | **0.15** | Awarded if candidate metrics moved in the expected direction (e.g. latency increase, CPU spike) during the degradation window. |

The total candidate evidence score is computed as:

$$\text{evidenceScore} = \sum w_i \cdot \mathbb{I}(\text{evidence}_i) \le 1.00$$

> [!IMPORTANT]
> The evidence score is a **weighted evidence index**, NOT a statistical probability.

### Confidence Bands:
- $\text{score} < 0.30$: `LOW`
- $0.30 \le \text{score} < 0.60$: `MODERATE`
- $0.60 \le \text{score} < 0.80$: `HIGH`
- $\text{score} \ge 0.80$: `VERY_HIGH`

---

## 4. Candidate Ranking & Tie-Breaking Rules

Candidate root causes are sorted deterministically using a three-tier sorting comparator:

1. **Evidence Score (`DESC`)**: Candidates with higher total evidence score rank first.
2. **Temporal Delta (`ASC`)**: If evidence scores are tied, the candidate whose anomaly occurred closer in time to the incident wins (smaller $\Delta t = t_{\text{incident}} - t_{\text{anomaly}}$).
3. **Candidate Resource UUID (`ASC`)**: If scores and temporal deltas are identical, lexicographical sorting on UUID provides deterministic tie-breaking.

### Primary Root Cause Selection:
A candidate is designated as the **PRIMARY** root cause if:
1. Candidate `evidenceScore` $\ge 0.30$, **AND**
2. Candidate is confirmed `ANOMALOUS`.

If no candidate meets both criteria, no primary root cause is assigned, and the analysis records an unconfirmed outcome.

---

## 5. Non-Mutating Contract
Triggering an RCA analysis via `POST /api/v1/incidents/{incidentId}/rca` persists the analysis and evidence records, but does **not** silently mutate the incident's status or root-cause field. The incident lifecycle remains governed exclusively by the incident state machine.
