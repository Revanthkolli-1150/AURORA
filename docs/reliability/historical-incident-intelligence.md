# Phase 2C — Historical Incident Intelligence

## Overview

The Historical Incident Intelligence capability deterministically identifies previously resolved incidents structurally similar to a given active or target incident.

It enables operators and downstream systems to query historical context, previous root causes, and resolution metrics without relying on non-deterministic AI/ML models.

## Architectural Mode

- **Deterministic Relational Pattern Matching**: Exact set-theoretic and categorical similarity.
- **Query-Time Derivation**: Derived directly from existing normalized relational tables; no materialized pattern tables or background synchronization jobs.
- **Read-Only**: Pure query operation. Does not acquire write locks or mutate incident, evidence, dependency, or RCA state.
- **Zero Probabilistic Inference**: Not ML, not LLM-based, not vector search, not embeddings. Similarity scores represent explicit relational degree of match, not probabilities.

## Historical Corpus Constraints

- **Scope**: Strictly `IncidentStatus.RESOLVED`.
- **Exclusions**:
  - `DETECTED`, `INVESTIGATING`, `DIAGNOSED`, `RECOVERING`, `VERIFYING` (active incidents)
  - `FAILED` incidents
  - The target incident itself (explicit self-exclusion)
- **Bounded Window**: Most recent **200** resolved incidents, ordered by:
  1. `resolved_at DESC`
  2. `id ASC`

## Deterministic Similarity Formula

For an incident signature consisting of resource type, anomalous metric names, 1-hop topology tokens, and severity:

$$\text{SimilarityScore} = (0.40 \times S_{\text{metric}}) + (0.30 \times S_{\text{topology}}) + (0.20 \times S_{\text{resource}}) + (0.10 \times S_{\text{severity}})$$

Where $\text{SimilarityScore} \in [0.00, 1.00]$.

### 1. Metric Similarity ($S_{\text{metric}}$) — Weight 0.40
Jaccard similarity on the deterministically sorted set of unique anomalous metric names:
$$S_{\text{metric}} = \frac{|M_{\text{target}} \cap M_{\text{historical}}|}{|M_{\text{target}} \cup M_{\text{historical}}|}$$
- Empty-set semantics:
  - Both sets empty: $1.0$
  - Exactly one set empty: $0.0$
  - Both non-empty: $\frac{\text{intersection}}{\text{union}}$

### 2. 1-Hop Topology Similarity ($S_{\text{topology}}$) — Weight 0.30
Direct 1-hop relationships represented as canonical directional tokens:
- Outgoing: `UPSTREAM:<targetResourceType>`
- Incoming: `DOWNSTREAM:<sourceResourceType>`
- Strictly 1-hop; no recursive graph traversal, no transitive closure, no BFS.
- Evaluated via Jaccard similarity with identical empty-set semantics.

### 3. Resource Type Match ($S_{\text{resource}}$) — Weight 0.20
- $1.0$ if target `resourceType == candidate resourceType`
- $0.0$ otherwise.

### 4. Severity Affinity ($S_{\text{severity}}$) — Weight 0.10
Deterministic ordinal distance mapping using explicit stable severity ranks independent of enum declaration order:
- `INFO` $\to 0$
- `LOW` $\to 1$
- `MEDIUM` $\to 2$
- `HIGH` $\to 3$
- `CRITICAL` $\to 4$

Affinity score:
- Rank difference $0$ (identical severity): $1.0$
- Rank difference $1$ (adjacent severity): $0.5$
- Rank difference $\ge 2$: $0.0$

### 5. RCA Context
Historical primary RCA root causes are exposed purely as **explanatory metadata**. They do **not** contribute numeric weight to the similarity score.

## Database Access & Complexity

- **Database Round Trips**: The number of database round trips is bounded independently of the number of candidates within the 200-candidate window (at most 6–7 batch queries total; zero per-candidate N+1 round trips).
- **Candidate Processing**: Pure in-memory calculation proportional to the number of retrieved candidates (bounded at $\le 200$).
- **Candidate Corpus**: Hard bounded at 200 at the SQL query layer (`PageRequest.of(0, 200)`).
- **Latency**: Design target is sub-50ms for the 200-candidate bounded historical window under representative data. Measured in-process scoring of 50 candidates executes in $<4\text{ ms}$.

## Deterministic Ranking & Pagination

Results are filtered by `minScore` ($\ge \text{minScore}$, default $0.30$) and sorted by:
1. `similarityScore DESC`
2. `resolvedAt DESC`
3. `historicalIncidentId ASC`

Bounded by `limit` (default $5$, maximum $20$).

## Explicit Non-Goals

- No Machine Learning or Neural Networks
- No LLMs or Generative AI
- No Embeddings or Vector Databases (e.g. pgvector, Milvus, Pinecone)
- No Automated Remediation or mutating actions
- No multi-hop graph traversals
