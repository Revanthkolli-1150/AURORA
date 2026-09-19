# AURORA Platform API Reference

The AURORA Reliability Platform exposes REST and WebSocket APIs through the central **API Gateway** (`platform/gateway`).

## Core Endpoints

### 1. Telemetry Ingestion
- `POST /api/v1/telemetry/probes`: Ingest probe execution results.
- `POST /api/v1/telemetry/metrics`: Ingest Prometheus / OTLP metrics.
- `GET /api/v1/telemetry/probes/live`: WebSocket stream for live probe status.

### 2. Service Level Objectives (SLOs)
- `GET /api/v1/slos`: List all registered SLOs.
- `POST /api/v1/slos`: Register a new SLO definition.
- `GET /api/v1/slos/:id/budget`: Retrieve current error budget and multi-window burn rate status.

### 3. Incidents
- `GET /api/v1/incidents`: Query active reliability incidents.
- `POST /api/v1/incidents/:id/mitigate`: Mark incident as mitigated.

### 4. Resilience & Chaos
- `GET /api/v1/chaos/experiments`: List active and completed experiments.
- `POST /api/v1/chaos/experiments`: Schedule a fault injection scenario.
- `POST /api/v1/chaos/experiments/:id/abort`: Emergency abort with rollback.
