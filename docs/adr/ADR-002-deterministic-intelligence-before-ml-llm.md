# ADR-002: Deterministic Statistical Intelligence Before ML/LLM

## Status
Accepted

## Date
2026-09-26

## Context
When building reliability platforms, there is a strong temptation to prematurely introduce generative AI, Large Language Models (LLMs), or complex black-box Machine Learning models for anomaly detection and Root Cause Analysis (RCA).

In production infrastructure management, non-deterministic outputs, hallucinations, and unexplainable inferences create severe operational hazards:
1. **Unpredictability & Non-Reproducibility**: An SRE responding to a critical incident requires deterministic, reproducible analysis. The exact same metric trace must yield the exact same anomaly classification and root-cause candidate every single time.
2. **Explainability**: Operations teams cannot act on remediation recommendations if the system cannot explain the mathematical or topological causal chain that led to the recommendation.
3. **Data Scarcity & Cold Start**: Complex ML models require massive, clean, pre-labeled incident datasets. In new infrastructure deployments, such training datasets do not exist.
4. **Latency & Cost Overhead**: Calling external LLM inference endpoints introduces seconds of latency and substantial financial cost during cascading incident storms.

## Decision
AURORA mandates **deterministic, statistical, and topological intelligence** as the foundational layer before any ML or LLM capabilities are introduced.

1. **Statistical Anomaly Detection (Phases 1C & 1C.1)**:
   - Evaluated using closed-form parametric statistics (Z-score via Sample Mean and Standard Deviation) and robust non-parametric statistics (Median Absolute Deviation with consistent normal scaling $1.4826$).
   - Scores are strictly normalized anomaly magnitudes bounded in $[0.0, 1.0)$, not synthetic probabilities.
   - Behavior on degenerate series (zero-variance / zero-MAD) is mathematically deterministic.
2. **Topological RCA (Phase 2B)**:
   - Root cause analysis relies on concrete, observable facts: temporal precedence, directed dependency graph relationships, metric anomalies, and directional telemetry movement.
   - Weights and ranking criteria are fixed and transparent.
3. **Evolutionary Path**:
   - Deterministic statistical and topological engines form the immutable baseline.
   - Phase 2C introduces Historical Incident Intelligence (pattern matching against past incidents).
   - Phase 2D introduces ML-Assisted RCA (learned graph weights).
   - Phase 3 introduces LLM-assisted operator summaries only after the underlying factual evidence chain is already proven.

## Consequences
### Positive
- 100% testable, explainable, and provably correct.
- Sub-millisecond execution times without external API dependencies.
- Zero risk of hallucinations during incident triage.

### Negative
- Does not automatically discover non-linear multi-variate correlations across unmodeled dependencies until future ML phases are reached.
