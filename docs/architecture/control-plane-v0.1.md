# AURORA Control Plane Architecture v0.1

## Executive Summary

AURORA is an infrastructure reliability platform designed to progress toward autonomous self-healing operations. The Control Plane v0.1 serves as the foundational centralized system-of-record and deterministic reliability core responsible for monitoring infrastructure assets, ingesting telemetry signals, tracking correlated reliability incidents, evaluating deterministic statistical anomalies and RCA evidence, and exposing read-only recovery proposals.

This document outlines the architectural principles, domain decomposition, request lifecycle, data persistence design, and evolutionary roadmap of the Control Plane v0.1.

---

## 1. Architectural Philosophy: The Modular Monolith

### The Problem with Premature Microservices
Distributed architectures incur severe operational penalties:
- **Network Latency & Unreliability**: Distributed transactions across networks introduce dual-write anomalies, eventual consistency lag, and partial failure modes.
- **Cognitive & Operational Friction**: Managing separate deployments, repository drift, distributed tracing overhead, and complex local developer setups before domain boundaries stabilize.
- **Premature Data Partitioning**: Early database splitting prevents ACID transactional guarantees between telemetry thresholds, incident generation, and remediation execution.

### The Modular Monolith Approach
AURORA Control Plane v0.1 is structured as a **modular monolith** within a single deployable artifact in Java 21 and Spring Boot 3:
- **Strict In-Process Domain Isolation**: Each business domain (`resources`, `telemetry`, `incidents`, `recovery`, `policies`) resides in an isolated package namespace.
- **Coupling Rules**: Cross-domain interaction occurs strictly via clean service interfaces, never by reaching directly into foreign JPA repositories or mutating foreign entities.
- **Shared Data Plane with Schema Boundaries**: Normalized relational tables with clear foreign key constraints ensure referential integrity while modeling boundaries that can later be extracted into independent databases.

```
+-------------------------------------------------------------------------+
|                       AURORA Control Plane v0.1                        |
|                                                                         |
|  +--------------------+   +---------------------+   +----------------+  |
|  |     resources      |   |      telemetry      |   |   incidents    |  |
|  | (Services, Pods,   |   | (Metrics, Logs,     |   | (Correlated    |  |
|  |  Databases, Hosts) |   |  Events, Latency)   |   |  Problems)     |  |
|  +---------+----------+   +----------+----------+   +-------+--------+  |
|            ^                         ^                      ^           |
|            |                         |                      |           |
|            +-------------------------+----------------------+           |
|                                      |                                  |
|                 +--------------------+--------------------+             |
|                 |                    |                    |             |
|                 v                    v                    v             |
|        +----------------+   +-----------------+   +---------------+     |
|        |    recovery    |   |    policies     |   | common/infra  |     |
|        | (Action Plans, |   | (SLO Thresholds,|   | (Tracing,     |     |
|        |  Remediation)  |   |  Guardrails)    |   |  Health)      |     |
|        +----------------+   +-----------------+   +---------------+     |
+-------------------------------------------------------------------------+
                                    |
                                    v
                 +--------------------------------------+
                 |      PostgreSQL Relational DB        |
                 | (Flyway Migrations, UTC Timestamps)  |
                 +--------------------------------------+
```

---

## 2. Domain Boundaries & Responsibilities

### 2.1 Resources Domain (`com.aurora.platform.resources`)
- **Responsibility**: Manages the authoritative registry of all physical, virtual, and containerized components monitored by AURORA.
- **Resource Types**: `SERVER`, `DATABASE`, `SERVICE`, `CONTAINER`, `APPLICATION`, `POD`.
- **Resource Status**: `HEALTHY`, `DEGRADED`, `CRITICAL`, `UNKNOWN`.
- **Contract Guarantee**: Idempotent resource registration; unique `(name, environment)` constraint to prevent dual-registration.

### 2.2 Telemetry Domain (`com.aurora.platform.telemetry`)
- **Responsibility**: Ingestion and retrieval of operational observations emitted by monitored nodes and agents.
- **Event Types**: `METRIC`, `LOG`, `TRACE`, `EVENT`. Focus in v0.1 is prioritized on time-series telemetry metrics (`cpu`, `memory`, `latency`, `error_rate`).
- **Domain Constraint**: Telemetry cannot be orphaned; every event must validate against a registered `resourceId`.

### 2.3 Incidents Domain (`com.aurora.platform.incidents`)
- **Core Principle**: **An Incident is NOT an Alert.** An alert is an isolated observation; an Incident represents a confirmed or correlated reliability disruption requiring diagnosis and resolution.
- **Severity**: `INFO`, `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`.
- **Status Lifecycle**: `DETECTED` -> `INVESTIGATING` -> `DIAGNOSED` -> `RECOVERING` -> `VERIFYING` -> `RESOLVED` (or `FAILED`).

### 2.4 Recovery Domain (`com.aurora.platform.recovery`)
- **Responsibility**: Generates and manages actionable recovery strategies for confirmed incidents.
- **Entity Model**:
  - `RecoveryPlan`: Encapsulates remediation reasoning, algorithmic confidence score (0.0 to 1.0), operational risk (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), and human approval flags.
  - `RecoveryAction`: Ordered discrete actions (`RESTART_POD`, `SCALE_OUT`, `ROLLBACK`, `FLUSH_CACHE`) with discrete state tracking (`PENDING`, `APPROVED`, `RUNNING`, `SUCCESS`, `FAILED`, `ROLLED_BACK`).

### 2.5 Policies Domain (`com.aurora.platform.policies`)
- **Responsibility**: Declarative reliability rules and guardrails specifying target thresholds and automated trigger actions.

---

## 3. End-to-End Request Flow

1. **Ingress & Correlation**:
   - Every incoming HTTP request passes through `CorrelationIdFilter`.
   - If an `X-Correlation-ID` header is present, it is preserved; otherwise, a new UUID trace ID is assigned.
   - The trace ID is bound to SLF4J MDC, enabling structured log correlation across all service logs, and returned in the HTTP response header.

2. **Validation Layer**:
   - Requests entering `@RestController` endpoints are validated using Jakarta Bean Validation annotations (`@Valid`, `@NotBlank`, `@NotNull`, `@Size`).
   - If constraints fail, `GlobalExceptionHandler` intercepts `MethodArgumentNotValidException` and produces a structured `400 Bad Request` with exact field error maps.

3. **Service & Transaction Execution**:
   - Controllers delegate to domain services (`@Service`).
   - Business boundaries are guarded with `@Transactional`.
   - Entities are transformed to immutable records (DTOs) before returning to the web layer to avoid leaking JPA persistence state.

4. **Persistence & Flyway**:
   - Entities are persisted via Spring Data JPA repositories.
   - All schema alterations are version-controlled via immutable Flyway SQL migrations.

---

## 4. Database Architecture

The data tier is backed by PostgreSQL 14+ with UTC time zone enforcement:

```
[ resources ]
    ├── id (UUID, PK)
    ├── name (VARCHAR, Indexed)
    ├── type (VARCHAR, Indexed)
    ├── status (VARCHAR, Indexed)
    ├── environment (VARCHAR, Indexed)
    ├── host (VARCHAR)
    ├── metadata (TEXT/JSON)
    ├── created_at (TIMESTAMPTZ)
    └── updated_at (TIMESTAMPTZ)
           │
           ├── 1:N ──> [ telemetry_events ]
           │               ├── id (UUID, PK)
           │               ├── resource_id (UUID, FK -> resources.id)
           │               ├── timestamp (TIMESTAMPTZ, Composite Indexed)
           │               ├── type (VARCHAR)
           │               ├── metric_name (VARCHAR, Indexed)
           │               ├── value (DOUBLE PRECISION)
           │               ├── unit (VARCHAR)
           │               └── metadata (TEXT/JSON)
           │
           └── 1:N ──> [ incidents ]
                           ├── id (UUID, PK)
                           ├── resource_id (UUID, FK -> resources.id)
                           ├── title (VARCHAR)
                           ├── severity (VARCHAR, Indexed)
                           ├── status (VARCHAR, Indexed)
                           ├── confidence (DOUBLE PRECISION)
                           ├── root_cause (TEXT)
                           ├── detected_at (TIMESTAMPTZ, Indexed)
                           ├── resolved_at (TIMESTAMPTZ)
                           ├── created_at (TIMESTAMPTZ)
                           └── updated_at (TIMESTAMPTZ)
                                   │
                                   └── 1:1 ──> [ recovery_plans ]
                                                   ├── id (UUID, PK)
                                                   ├── incident_id (UUID, FK -> incidents.id)
                                                   ├── reasoning (TEXT)
                                                   ├── confidence (DOUBLE PRECISION)
                                                   ├── risk (VARCHAR)
                                                   ├── approval_required (BOOLEAN)
                                                   └── created_at (TIMESTAMPTZ)
                                                           │
                                                           └── 1:N ──> [ recovery_actions ]
                                                                           ├── id (UUID, PK)
                                                                           ├── recovery_plan_id (UUID, FK -> recovery_plans.id)
                                                                           ├── action_type (VARCHAR)
                                                                           ├── target (VARCHAR)
                                                                           ├── status (VARCHAR)
                                                                           ├── result (TEXT)
                                                                           ├── started_at (TIMESTAMPTZ)
                                                                           └── completed_at (TIMESTAMPTZ)
```

---

## 5. API Boundaries (v0.1)

| Method | Endpoint | Description | Status Codes |
|---|---|---|---|
| `POST` | `/api/v1/resources` | Register a new monitored resource | `201 Created`, `400 Bad Request`, `409 Conflict` |
| `GET` | `/api/v1/resources` | List resources (supports `environment`, `type`, `status` filters) | `200 OK` |
| `GET` | `/api/v1/resources/{id}` | Get resource details by UUID | `200 OK`, `404 Not Found` |
| `POST` | `/api/v1/telemetry` | Ingest time-series telemetry metric | `201 Created`, `400 Bad Request`, `404 Not Found` |
| `GET` | `/api/v1/telemetry/resource/{resourceId}` | Retrieve telemetry timeline for a resource | `200 OK`, `404 Not Found` |
| `GET` | `/api/v1/incidents` | List correlated incidents (filters: `resourceId`, `status`, `severity`) | `200 OK` |
| `GET` | `/api/v1/incidents/{id}` | Get incident root cause and diagnostic details | `200 OK`, `404 Not Found` |
| `GET` | `/api/v1/incidents/{incidentId}/recovery-plan` | Get AI/rule-generated recovery plan and actions | `200 OK`, `404 Not Found` |
| `GET` | `/actuator/health` | Service health status including database connectivity | `200 OK`, `503 Service Unavailable` |

---

## 6. Future Microservice Extraction Strategy

When telemetry volume or incident evaluation throughput demands independent horizontal scaling:
1. **Identify Bottlenecks**: Telemetry ingestion will likely be the first high-throughput boundary.
2. **Introduce Domain Events**: Replace direct in-process method invocations with an internal Spring ApplicationEvent publisher.
3. **Externalize Messaging**: Swap internal event publishers with Apache Kafka or Google Cloud Pub/Sub.
4. **Physical Service Extraction**:
   - Extract `telemetry-service` into an independent binary with its own time-series / partitioned storage.
   - Retain `control-plane` as the orchestrator of resources, incidents, and recovery decisions.
   - Zero breaking changes to public REST API contracts through backwards-compatible API gateway routing.
