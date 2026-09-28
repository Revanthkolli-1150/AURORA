# ⚡ AURORA | Reliability Platform

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Node.js%2022%20%7C%20TypeScript-61dafb.svg)](https://nodejs.org)
[![Architecture](https://img.shields.io/badge/Architecture-Enterprise%20Monorepo-green.svg)](#repository-layout)
[![Reliability](https://img.shields.io/badge/SRE-Autonomous%20Resilience%20Platform-purple.svg)](#capabilities)

> **AURORA** is an autonomous reliability platform that observes software systems, understands failures, detects anomalies, investigates root causes, predicts incidents, plans safe recovery, executes controlled recovery, verifies the result, and learns from incidents.

---

## 🏛️ Architecture: Current vs. Target

### Current Execution Architecture (Modular Monolith)
Per [ADR-001](docs/adr/ADR-001-modular-control-plane.md), AURORA's active backend is currently implemented as a single, production-grade **Modular Monolith**:
- **Control Plane**: `platform/aurora-control-plane` (Java 21 LTS, Spring Boot 3.3.4, Spring Data JPA, Flyway V1–V5)
- **Database**: PostgreSQL 16
- **Console Frontend**: `frontend/aurora-console` (React / Vite)

### Long-Term Target Architecture
The canonical repository structure below represents the long-term architectural destination for future service extractions per [ADR-003](docs/adr/ADR-003-controlled-future-service-extraction.md).

```
aurora-reliability-platform/
│
├── README.md                  # Comprehensive repository documentation
├── LICENSE                    # Apache-2.0 open-source license
├── .gitignore                 # Universal git ignore configuration
├── .env.example               # Environment variables template
├── docker-compose.yml         # Container orchestration
│
├── docs/                      # Technical design & documentation
│   ├── architecture/          # System design specifications
│   ├── api/                   # REST/gRPC API specifications
│   ├── adr/                   # Architecture Decision Records
│   ├── ai/                    # Intelligence & RCA specifications
│   ├── reliability/           # Reliability & anomaly detection standards
│   └── security/              # Security & access control
│
├── platform/
│   ├── aurora-control-plane/  # Current executable modular monolith backend
│   ├── gateway/               # Target API gateway (future)
│   ├── identity-service/      # Target IAM & auth service (future)
│   ├── incident-service/      # Target extracted incident service (future)
│   ├── telemetry-service/     # Target high-throughput streaming ingest (future)
│   ├── policy-service/        # Target safety policy service (future)
│   └── recovery-service/      # Target autonomous remediation service (future)
│
├── agent/                     # Edge execution agents
│   └── aurora-agent/          # Distributed synthetic probe runner & host health monitor
│
├── intelligence/              # Analytics & Machine Learning engines
│   ├── anomaly-engine/        # Statistical and ML-based metric outlier detection
│   ├── prediction-engine/     # Error budget exhaustion time-series forecasting
│   ├── rca-engine/            # Graph causality and topological root-cause analyzer
│   └── ai-engine/             # LLM incident summarization & interactive runbook copilot
│
├── frontend/                  # Modern operator interfaces
│   └── aurora-console/        # Glassmorphic React/Vite SRE Command Center UI
│
├── infrastructure/            # Cloud & orchestration automation
│   ├── docker/                # Multi-stage production container Dockerfiles
│   ├── kubernetes/            # Production K8s manifests and CRDs
│   ├── terraform/             # Cloud infrastructure as code
│   └── helm/                  # Helm charts for automated cluster deployment
│
├── observability/             # End-to-end monitoring stack configs
│   ├── otel/                  # OpenTelemetry collector configuration
│   ├── prometheus/            # Prometheus scrape targets & alert rules
│   ├── grafana/               # Pre-provisioned SRE dashboards
│   └── loki/                  # Log aggregation configuration
│
├── experiments/               # Resilience & performance research
│   ├── ml/                    # Anomaly training datasets and model notebooks
│   ├── chaos/                 # Chaos injection scenario manifests
│   └── benchmarks/            # Throughput & latency stress harnesses
│
├── tests/                     # Verification test suites
│   ├── integration/           # Cross-service end-to-end integration tests
│   ├── chaos/                 # Automated resilience and safety guardrail tests
│   └── performance/           # Load testing scenarios
│
└── scripts/                   # Developer automation & lifecycle scripts
```

---

## 🌟 Capabilities

- **📊 Autonomous SLO Governance**: Multi-Window Multi-Burn-Rate (MWMBR) calculations, error budget exhaustion forecasting, and automated deployment gate freezes.
- **🛰️ Distributed Synthetic Canaries**: Sub-second synthetic transaction probes running globally over HTTP/S, gRPC, and WebSockets.
- **🧠 Intelligence & AI RCA**: Graph causality and predictive forecasting to isolate degraded microservices and generate automated runbook steps.
- **🧪 Safety-Gated Chaos Engineering**: Controlled fault injection (latency, packet loss, blackholes) with autonomous circuit-breaker aborts.
- **🖥️ Glassmorphic SRE Cockpit**: Live telemetry visualization, error budget tracking, and real-time incident command.

---

## 🚀 Quick Start

### Prerequisites
- **Node.js**: `>= 20.0.0` (tested on Node `v22.11.0`)
- **npm**: `>= 10.0.0`
- **Docker & Docker Compose** (optional, for running the full stack)

### 1. Installation
```bash
# Clone the repository
git clone https://github.com/organization/aurora-reliability-platform.git
cd aurora-reliability-platform

# Install dependencies across all workspaces
npm install
```

### 2. Launch Development Stack
```bash
# Run the Aurora SRE Console UI
npm run dev:console

# Run the API Gateway
npm run dev:gateway

# Run the Edge Probing Agent
npm run dev:agent
```

### 3. Run Full Docker Compose Stack
```bash
docker compose up -d
```

---

## 📜 License
Licensed under the [Apache License, Version 2.0](LICENSE).
