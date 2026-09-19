# AURORA System Architecture Specification

## 1. Executive Summary

AURORA is a unified cloud reliability platform engineered to bridge the gap between passive observability (metrics, logs, traces) and active resilience engineering (synthetic verification, SLO error-budget governance, automated remediation, and chaos experiments).

Traditional SRE stacks fragment monitoring, alerts, and chaos testing across disparate silos. AURORA unifies these domains into a closed-loop reliability feedback system:

$$\text{Observe} \longrightarrow \text{Assess (SLO / Budget)} \longrightarrow \text{Stress (Chaos / Synthetic)} \longrightarrow \text{Enforce / Remediate}$$

---

## 2. Core Functional Pillars

### 2.1. SLO & Error Budget Lifecycle Engine
- **Multi-Window Multi-Burn-Rate (MWMBR)**: Implements Google SRE alerting standards (e.g. 1h/5% burn, 6h/10% burn, 3-day / 30-day rolling evaluation windows).
- **Automated Freeze Triggers**: Real-time evaluation of error budget burn rates with programmable webhook triggers to halt progressive deployments if an error budget is depleted.
- **Budget Exhaustion Forecasting**: Predictive linear and time-series projection indicating when an active degradation will breach contract terms.

### 2.2. Distributed Synthetic Canary Probing
- Continuous user journey execution across configurable protocols:
  - `HTTP/REST`: Status codes, JSON schema validation, latency thresholds, SSL certificate validation.
  - `gRPC`: Protobuf payload checks and streaming RPC heartbeats.
  - `WebSocket`: Connection handshake, frame exchange roundtrip timing.
- Geographically and network-topologically distributed probe runners.

### 2.3. Controlled Resilience & Chaos Injection
- Precision fault-injection orchestrator:
  - **Latency Injection**: Introduce jitter and synthetic delay to critical dependencies.
  - **Error Cascades**: Inject HTTP 5xx / gRPC Internal faults into API gateways.
  - **Service Isolation**: Blackhole egress traffic to mock third-party failures.
- **Circuit-Breaker Safety Interlock**: If any active chaos experiment causes an SLI breach above safe guardrail margins, the safety controller initiates an immediate emergency abort and rollback.

### 2.4. Signal Correlation & Incident Command
- Grouping of synthetic probe failures, error budget burn alerts, and anomalous metric shifts into correlated **Reliability Incidents**.
- Context-rich blast radius estimation and automated runbook linking.

---

## 3. Subsystem Breakdown

### 3.1. `packages/types`
The single source of truth for the AURORA domain model. Contains TypeScript definitions for:
- `SLO`, `SLI`, `ErrorBudget`, `BurnRateWindow`
- `ProbeConfig`, `ProbeExecutionResult`, `HealthStatus`
- `ChaosExperiment`, `FaultType`, `SafetyGuardrail`
- `ReliabilityAlert`, `IncidentState`, `BlastRadius`

### 3.2. `services/engine`
The operational backend daemon responsible for:
- Periodic scheduling of synthetic probes.
- Ingestion of probe results and telemetry streams.
- In-memory sliding window evaluation of SLIs against target thresholds.
- Chaos experiment state machine and blast radius containment.

### 3.3. `apps/dashboard`
High-density, low-latency SRE cockpit:
- Dark-mode HUD with real-time status matrices.
- Interactive error budget burn-rate graphs.
- Live probe execution traces.
- Chaos experiment controls with single-click abort triggers.
