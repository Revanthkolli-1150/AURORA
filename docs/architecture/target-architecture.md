# AURORA Target Architecture

## 1. Architectural Vision
AURORA's long-term vision is an autonomous, self-healing reliability platform spanning distributed agents, high-throughput streaming telemetry, specialized intelligence engines, and automated recovery controllers.

The target architecture defines the destination layout for the entire platform when scale, operational independence, and technological divergence warrant microservice extraction.

> [!IMPORTANT]
> The target architecture is an architectural roadmap and destination map. It is **NOT** a mandate to prematurely create empty directories, placeholder services, or distributed infrastructure before operational requirements justify them.

## 2. Target Repository Structure

```
aurora-reliability-platform/
│
├── README.md
├── LICENSE
├── .gitignore
├── .env.example
├── docker-compose.yml
│
├── docs/
│   ├── architecture/
│   ├── api/
│   ├── adr/
│   ├── ai/
│   ├── reliability/
│   └── security/
│
├── platform/
│   ├── aurora-control-plane/        <-- Current executable modular monolith
│   ├── gateway/                     <-- Target API gateway (future)
│   ├── identity-service/            <-- Target RBAC & authentication (future)
│   ├── incident-service/            <-- Target extracted incident manager (future)
│   ├── telemetry-service/           <-- Target high-throughput ingest engine (future)
│   ├── policy-service/              <-- Target safety policy orchestrator (future)
│   └── recovery-service/            <-- Target automated execution service (future)
│
├── agent/
│   └── aurora-agent/                <-- Edge host/node telemetry collection daemon
│
├── intelligence/
│   ├── anomaly-engine/              <-- Streaming statistical anomaly detector
│   ├── prediction-engine/           <-- Early failure prediction
│   ├── rca-engine/                  <-- Multi-hop graph & ML root cause analysis
│   └── ai-engine/                   <-- LLM/Agentic incident investigator & runbook generator
│
├── frontend/
│   └── aurora-console/              <-- Single-pane-of-glass SRE console
│
├── infrastructure/
│   ├── docker/
│   ├── kubernetes/
│   ├── terraform/
│   └── helm/
│
├── observability/
│   ├── otel/
│   ├── prometheus/
│   ├── grafana/
│   └── loki/
│
├── experiments/
│   ├── ml/
│   ├── chaos/
│   └── benchmarks/
│
├── tests/
│   ├── integration/
│   ├── chaos/
│   └── performance/
│
└── scripts/
```

## 3. Evolutionary Extraction Strategy
Per [ADR-001](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-001-modular-control-plane.md) and [ADR-003](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md), AURORA evolves in controlled phases:

1. **Current State (Phases 1A–2D-A Complete; Phase 2D-B Deferred)**: `platform/aurora-control-plane` hosts all core domain packages (`resource`, `telemetry`, `dependency`, `incident`, `intelligence`, `policy`) as an in-memory modular monolith with deterministic RCA, historical similarity intelligence, and empirical graph edge weighting.
2. **Phase 2D-B (Deferred)**: Supervised candidate scoring deferred pending authentic operator feedback ground truth.
3. **Phase 3 (AI Investigator / LLM Summaries)**: Python/ML/LLM integration via bounded gRPC/REST APIs or service adapters for incident summarization and runbook assistance.
4. **Phase 4 & 5 (Recovery)**: Declarative recovery planning followed by safe autonomous actuation, guarded by blast radius controls and human-in-the-loop approvals.
5. **Future Service Extraction**: Extracting `telemetry-service` or `intelligence/` only when throughput or compute boundaries require independent horizontal scaling.
