# AURORA Control Plane (v0.1)

The **AURORA Control Plane** is the foundational system-of-record and deterministic reliability core for the AURORA platform.

It is implemented as a production-grade **modular monolith** in **Java 21** and **Spring Boot 3.3**, backed by **PostgreSQL** and **Flyway** migrations.

---

## Key Features

- **Monitored Resource Registry**: Inventory and tracking of physical servers, containers, pods, databases, and microservices.
- **Telemetry Ingestion Engine**: Structured ingestion and retrieval of time-series infrastructure metrics and logs.
- **Correlated Incident Tracking**: Representation of verified reliability problems with severity, lifecycle statuses, confidence metrics, and root cause analysis.
- **Read-Only Recovery Proposal Scaffolding**: Retrieval of proposed mitigation plans and risk evaluations (Phase 1D/2B foundation; no automated actuation or execution).
- **Enterprise Reliability**: Distributed trace correlation via `X-Correlation-ID`, centralized `@RestControllerAdvice` error responses, and Spring Boot Actuator health endpoints.

---

## Tech Stack

- **Runtime**: Java 21 LTS
- **Framework**: Spring Boot 3.3.4
- **Build Tool**: Apache Maven 3.9+
- **Persistence**: Spring Data JPA / Hibernate
- **Database**: PostgreSQL 16+
- **Database Migrations**: Flyway
- **Validation**: Jakarta Bean Validation
- **Observability**: Spring Boot Actuator, SLF4J MDC Tracing
- **Testing**: JUnit 5, Mockito, Spring Boot Test, Testcontainers PostgreSQL

---

## Project Structure

```
com.aurora.platform
├── common/             # Cross-cutting web filters, exception handlers, standardized API responses
│   ├── api/
│   ├── config/
│   ├── exception/
│   └── web/
├── resource/           # Monitored resource registry & lifecycle (controller, service, repository, entity, DTOs)
├── telemetry/          # Metric telemetry signal ingestion & retrieval (controller, service, repository, entity, DTOs)
├── dependency/         # Directed topological dependency graph (controller, service, repository, entity, DTOs)
├── incident/           # Correlated incidents & anomaly evidence (controller, service, repository, entity, DTOs)
├── intelligence/       # Deterministic intelligence engines
│   ├── anomaly/        # Statistical anomaly detection (Z-score & MAD detectors, model, policy)
│   └── rca/            # Deterministic root cause analysis (candidate, evidence, ranking, service)
├── policy/             # Reliability thresholds & guardrails (service, repository, entity, DTOs)
├── recovery/           # Read-only recovery proposal scaffolding (Phase 1D/2B foundation; execution deferred to future phases)
└── infrastructure/     # Spring Boot Actuator custom health indicators and properties
```

---

## Required Environment Variables

| Variable | Default Value | Description |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port for the Control Plane web server |
| `AURORA_ENV` | `development` | Deployment environment identifier (`development`, `staging`, `production`) |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `aurora` | PostgreSQL database name |
| `DB_USERNAME` | `aurora` | PostgreSQL user |
| `DB_PASSWORD` | `aurora` | PostgreSQL password |
| `DB_POOL_MAX` | `10` | HikariCP maximum pool size |

---

## Running Locally

### 1. Prerequisites
- Java 21 LTS (`java -version`)
- Maven 3.9+ (`mvn -v`)
- A running PostgreSQL instance (or use Docker Compose below)

### 2. Start PostgreSQL
```bash
docker run --name aurora-postgres -e POSTGRES_DB=aurora -e POSTGRES_USER=aurora -e POSTGRES_PASSWORD=aurora -p 5432:5432 -d postgres:16-alpine
```

### 3. Run the Application
```bash
cd platform/aurora-control-plane
mvn spring-boot:run
```

The service will start on port `8080` and run Flyway migrations automatically.

---

## Running with Docker Compose

From the project root:

```bash
# 1. Copy the environment template
cp .env.example .env

# 2. Start PostgreSQL and Control Plane
docker compose up --build -d

# 3. Check logs
docker compose logs -f aurora-control-plane
```

To stop:
```bash
docker compose down
```

---

## Running Tests

Run all unit tests, slice tests, Flyway migration tests, and integration tests:

```bash
cd platform/aurora-control-plane
mvn clean test
```

To package and verify:
```bash
mvn clean verify
```

---

## Resource Management Domain (Phase 1A)

The **Resource Management** domain is the foundational asset registry for the AURORA Control Plane. It inventories, identifies, and tracks the lifecycle and operational health of monitored infrastructure components across cloud, container, and on-premises environments.

### Supported Enums
- **Resource Types**: `SERVER`, `DATABASE`, `SERVICE`, `CONTAINER`, `APPLICATION`, `POD`
- **Resource Statuses**: `HEALTHY`, `DEGRADED`, `CRITICAL`, `UNKNOWN`

### Resource Lifecycle
1. **Registration**: An infrastructure component or service registers via `POST /api/v1/resources`. If status is omitted, it defaults to `HEALTHY`. A unique UUID is assigned and indexed.
2. **Observation**: Telemetry probes and metrics report signals referencing the resource's `id`.
3. **Health Transition**: As reliability anomalies or SLO violations are detected, the status transitions across `HEALTHY` -> `DEGRADED` -> `CRITICAL`.
4. **Resolution**: Following automated remediation or manual recovery actions, the status restores to `HEALTHY`.

---

## API Endpoints

### 1. Resources API
- `POST /api/v1/resources` - Register a monitored resource (returns `201 Created` with `Location` header)
- `GET /api/v1/resources` - List monitored resources (optional query parameters: `environment`, `type`, `status`)
- `GET /api/v1/resources/{id}` - Retrieve resource by UUID (returns `200 OK` or `404 Not Found`)

#### Example: Register Resource (Without explicit status — defaults to HEALTHY)
```bash
curl -X POST http://localhost:8080/api/v1/resources \
  -H "Content-Type: application/json" \
  -d '{
    "name": "aurora-postgres",
    "type": "DATABASE",
    "environment": "development",
    "host": "localhost"
  }'
```

**Response (`201 Created`):**
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "aurora-postgres",
  "type": "DATABASE",
  "status": "HEALTHY",
  "environment": "development",
  "host": "localhost",
  "metadata": {},
  "createdAt": "2026-09-23T15:30:00Z",
  "updatedAt": "2026-09-23T15:30:00Z"
}
```

#### Example: Register Resource with Metadata & Status
```bash
curl -X POST http://localhost:8080/api/v1/resources \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: req-abc-123" \
  -d '{
    "name": "auth-service",
    "type": "SERVICE",
    "status": "HEALTHY",
    "environment": "production",
    "host": "auth-node-01.internal",
    "metadata": { "region": "us-east-1", "version": "1.2.0" }
  }'
```

#### Example: Query / List Resources
```bash
# List all resources
curl http://localhost:8080/api/v1/resources

# Filter by environment and type
curl "http://localhost:8080/api/v1/resources?environment=production&type=SERVICE"
```

#### Example: Get Resource by ID
```bash
curl http://localhost:8080/api/v1/resources/3fa85f64-5717-4562-b3fc-2c963f66afa6
```

### 2. Resource Dependency Modeling API (Phase 2A)
AURORA models directed dependencies between monitored infrastructure components, establishing the structural topology for downstream impact analysis and future Root Cause Analysis (RCA).

#### Dependency Semantics
- **Semantics**: `sourceResource DEPENDS_ON targetResource` ($A \to B$)
- **Outgoing Edges (`dependencies`)**: Resources that the given resource depends on.
- **Incoming Edges (`dependents`)**: Resources that depend on the given resource.

```
Frontend Service
      |
      | DEPENDS_ON (outgoing edge from Frontend, incoming edge to API)
      v
API Gateway
      |
      | DEPENDS_ON (outgoing edge from API, incoming edge to PostgreSQL)
      v
PostgreSQL Database
```

#### Invariants & Constraints
- **Directionality**: Directed edges ($A \to B$ is distinct from $B \to A$).
- **No Self-Dependencies**: Enforced at both application and database check constraint levels (`source_resource_id <> target_resource_id`). Note: Self-loop prevention is strictly a self-dependency check, NOT general cycle detection across multi-hop graphs (general cycle detection is a future roadmap capability).
- **No Duplicate Directed Edges**: Enforced by database unique constraint `(source_resource_id, target_resource_id, dependency_type)`.
- **Cascade Deletion**: If a resource is deleted, all its incoming and outgoing dependency edges are automatically cascade-deleted.

#### Endpoints
- `POST /api/v1/resources/{resourceId}/dependencies` - Create a directed dependency (HTTP 201 Created)
- `GET /api/v1/resources/{resourceId}/dependencies` - List outgoing dependencies (resources `{resourceId}` depends on, HTTP 200 OK)
- `GET /api/v1/resources/{resourceId}/dependents` - List incoming dependents (resources that depend on `{resourceId}`, HTTP 200 OK)
- `DELETE /api/v1/resources/{resourceId}/dependencies/{dependencyId}` - Delete a dependency belonging to the source resource (HTTP 204 No Content)

#### Example: Create Dependency
```bash
curl -X POST http://localhost:8080/api/v1/resources/0759a6a9-d614-431d-8985-36adb4712bde/dependencies \
  -H "Content-Type: application/json" \
  -d '{
    "targetResourceId": "b8c4c092-0877-49e4-8ad9-1265b3d539c7",
    "dependencyType": "DEPENDS_ON"
  }'
```

**Response (`201 Created`):**
```json
{
  "id": "1b84cc01-1954-4435-891c-c57d2b596f20",
  "sourceResourceId": "0759a6a9-d614-431d-8985-36adb4712bde",
  "targetResourceId": "b8c4c092-0877-49e4-8ad9-1265b3d539c7",
  "dependencyType": "DEPENDS_ON",
  "createdAt": "2026-09-24T17:30:57.250Z"
}
```

#### Example: Query Dependencies & Dependents
```bash
# Get outgoing dependencies (what does Frontend depend on?)
curl http://localhost:8080/api/v1/resources/0759a6a9-d614-431d-8985-36adb4712bde/dependencies

# Get incoming dependents (what depends on API Gateway?)
curl http://localhost:8080/api/v1/resources/b8c4c092-0877-49e4-8ad9-1265b3d539c7/dependents
```

#### Example: Delete Dependency
```bash
curl -X DELETE http://localhost:8080/api/v1/resources/0759a6a9-d614-431d-8985-36adb4712bde/dependencies/1b84cc01-1954-4435-891c-c57d2b596f20
```

### 3. Telemetry API (Phase 1B)
AURORA receives, validates, and persists metric observations associated with registered resources.

- `POST /api/v1/telemetry` - Ingest metric telemetry event (HTTP 201 Created)
- `GET /api/v1/telemetry/resource/{resourceId}` - Retrieve telemetry for a resource ordered chronologically (oldest → newest, HTTP 200 OK)
- `GET /api/v1/telemetry/resource/{resourceId}?metricName={name}` - Filter telemetry for a resource by specific metric name (HTTP 200 OK)

#### Validation Rules
- `resourceId`: UUID required, must match an existing registered resource (returns HTTP 404 if not found).
- `timestamp`: UTC Instant required (returns HTTP 400 if omitted).
- `type`: Enum required. In Phase 1B, only `METRIC` is supported; unsupported types (`LOG`, `TRACE`, `EVENT`) are rejected with HTTP 400 (`VALIDATION_ERROR`).
- `metricName`: Required, sensible length between 1 and 255 characters.
- `value`: Double precision value required.
- `unit`: Required, sensible length between 1 and 50 characters (e.g., `percent`, `mb`, `bytes`, `connections`).
- `metadata`: Optional JSON key-value map.

#### Example: Ingest Telemetry Metric
```bash
curl -X POST http://localhost:8080/api/v1/telemetry \
  -H "Content-Type: application/json" \
  -H "X-Correlation-ID: req-tel-001" \
  -d '{
    "resourceId": "cd90c6f9-6086-4cbc-a706-0506817a18a7",
    "timestamp": "2026-09-24T00:30:00Z",
    "type": "METRIC",
    "metricName": "cpu_usage",
    "value": 78.4,
    "unit": "percent",
    "metadata": { "core": 0 }
  }'
```

#### Example: Query Telemetry for a Resource
```bash
# Retrieve all metrics ordered oldest -> newest
curl http://localhost:8080/api/v1/telemetry/resource/cd90c6f9-6086-4cbc-a706-0506817a18a7

# Filter metrics by metric name
curl "http://localhost:8080/api/v1/telemetry/resource/cd90c6f9-6086-4cbc-a706-0506817a18a7?metricName=cpu_usage"
```

### 4. Anomaly Detection Engine & Architecture (Phase 1C & Phase 1C.1)
AURORA provides a deterministic, pluggable statistical anomaly detection engine over historical telemetry series. It evaluates whether an incoming observation or the latest recorded metric event deviates significantly from historical baseline behavior.

#### Detector Architecture
Statistical detectors implement the common `AnomalyDetector` interface and are registered into a lookup map (`Map<AnomalyDetectorType, AnomalyDetector>`) in `AnomalyDetectionServiceImpl`:

```
                 +--------------------------+
                 |  AnomalyDetectionService |
                 +------------+-------------+
                              | (resolves configured detector)
                              v
                 +--------------------------+
                 |     AnomalyDetector      |
                 +------------+-------------+
                              |
               +--------------+--------------+
               |                             |
               v                             v
  +-------------------------+   +-------------------------+
  |  ZScoreAnomalyDetector  |   |   MadAnomalyDetector    |
  |   (Standard Deviation)  |   |  (Median Absolute Dev.) |
  +-------------------------+   +-------------------------+
```

#### Detector Selection Configuration
Detectors are chosen via explicit application configuration without modifying service logic:
```yaml
aurora:
  intelligence:
    anomaly:
      detector: ${AURORA_ANOMALY_DETECTOR:Z_SCORE} # Options: Z_SCORE | MAD
      default-threshold: 3.0
      min-sample-count: 10
```
- **Default**: `Z_SCORE` (guarantees 100% backward compatibility with Phase 1C).
- **Alternative**: `MAD` (Median Absolute Deviation for robust anomaly detection).
- **Validation**: Any invalid detector string is rejected during configuration binding with a clear `IllegalArgumentException`.

#### 1. Z-Score Anomaly Detector (`ZScoreAnomalyDetector`)
Uses Mean / Standard-Deviation Based Anomaly Detection:
- **Sample Mean:** $\mu = \frac{1}{N}\sum_{i=1}^N x_i$
- **Sample Standard Deviation:** Computed with Bessel's correction ($N - 1$ denominator):
  $$s = \sqrt{\frac{1}{N-1}\sum_{i=1}^N (x_i - \mu)^2}$$
- **Standardized Z-Score:**
  $$z = \frac{x - \mu}{s}$$
- **Classification Rule:** Flagged `ANOMALOUS` when $|z| \ge \text{threshold}$ (default: $3.0$).
- **Normalized Anomaly Magnitude (Score):**
  $$\text{anomalyScore} = 1 - \exp\left(-\frac{|z|}{\text{threshold}}\right)$$
- **Zero Variance Behavior ($s = 0$):**
  - If $x = \mu$: `NORMAL`, $z = 0.0$, $\text{anomalyScore} = 0.0$.
  - If $x \ne \mu$: `ANOMALOUS`, $z = \text{null}$ (division by zero avoided), $\text{anomalyScore} = 1.0$.

#### 2. Robust MAD Anomaly Detector (`MadAnomalyDetector`)
Uses Median Absolute Deviation (MAD), a robust non-parametric statistical estimator with a 50% breakdown point, making it highly resistant to historical outliers in the baseline:
- **Median:** $\tilde{x} = \text{median}(X)$
- **Absolute Deviations:** $AD_i = |X_i - \tilde{x}|$
- **Median Absolute Deviation (MAD):**
  $$\text{MAD} = \text{median}(|X_i - \tilde{x}|)$$
- **Robust Scale:** Uses the standard consistency factor $1.4826$ for asymptotic normal consistency:
  $$\text{robustScale} = 1.4826 \times \text{MAD}$$
- **Robust Standardized Deviation ($\text{robustZ}$):**
  $$\text{robustZ} = \frac{|x - \tilde{x}|}{\text{robustScale}}$$
- **Classification Rule:** Flagged `ANOMALOUS` when $\text{robustZ} \ge \text{threshold}$ (default: $3.0$).
- **Normalized Anomaly Magnitude (Score):**
  $$\text{anomalyScore} = 1 - \exp\left(-\frac{\text{robustZ}}{\text{threshold}}\right)$$
- **Zero-MAD Behavior ($\text{MAD} = 0$, e.g. constant series):**
  - If $x = \tilde{x}$: `NORMAL`, $\text{robustZ} = 0.0$, $\text{anomalyScore} = 0.0$.
  - If $x \ne \tilde{x}$: `ANOMALOUS`, $\text{robustZ} = \text{null}$ (standardized score is undefined when robust scale is zero), $\text{anomalyScore} = 1.0$.

#### Robust Statistics: Z-Score vs MAD Rationale
MAD is **not** universally superior to Z-Score, but offers distinct properties under specific telemetry profiles:
- **Masking Resistance:** If a telemetry metric previously experienced a major transient spike within the rolling baseline window, sample standard deviation $s$ inflates quadratically, artificially depressing future Z-scores (masking subsequent legitimate anomalies). MAD is robust against up to 50% contamination by outliers, maintaining an accurate baseline scale.
- **Parametric Efficiency:** When metrics closely follow an undisturbed normal distribution, Z-Score has higher statistical efficiency than MAD.
- **Unified Semantics:** Both detectors share identical minimum sample requirements ($N \ge 10$), identical thresholding semantics (default $3.0$), and the identical exponential magnitude normalization formula.

#### Normalized Anomaly Magnitude vs Probability
The calculated `anomalyScore` is a **normalized anomaly magnitude** bounded in $[0.0, 1.0)$:
- **It is NOT an empirical probability.** A score of $0.632$ at threshold does not mean a 63.2% chance of failure; it indicates the relative magnitude of baseline deviation mapped smoothly across severity bands.
- Monotonically increases with standardized deviation without hard step-clipping.

| Standardized Deviation ($|z|$ or $\text{robustZ}$) | Score ($\text{threshold} = 3.0$) | Severity Classification |
|---|---|---|
| $0.0$ | $0.000$ | LOW ($< 0.50$) |
| $1.0$ | $0.283$ | LOW ($< 0.50$) |
| $3.0$ (threshold) | $0.632$ | MEDIUM ($[0.50, 0.80)$) |
| $6.0$ ($2\times$) | $0.865$ | HIGH ($[0.80, 0.95)$) |
| $9.0$ ($3\times$) | $0.950$ | CRITICAL ($\ge 0.95$) |
| $15.0$ ($5\times$) | $0.993$ | CRITICAL ($\ge 0.95$) |

#### Endpoints
- `GET /api/v1/intelligence/anomaly/resource/{resourceId}?metricName={name}` - Evaluates the latest recorded telemetry event against prior historical baseline.
- `GET /api/v1/intelligence/anomaly/resource/{resourceId}?metricName={name}&value={val}` - Evaluates an explicit observation value against all historical baseline events.
- Optional parameter: `&threshold={val}` - Overrides the default sensitivity threshold (default: `3.0`).

#### Example: Evaluate Observation
```bash
curl "http://localhost:8080/api/v1/intelligence/anomaly/resource/cd90c6f9-6086-4cbc-a706-0506817a18a7?metricName=cpu_usage&value=91.0&threshold=3.0"
```

### 5. Incident Intelligence & Anomaly Correlation (Phase 1D)
AURORA converts meaningful statistical anomalies into persistent, correlated **Incidents**, preserving the relationship between each incident and the anomaly evidence that precipitated it.

#### Core Distinction
- **Telemetry**: Raw metric observation over time.
- **Anomaly**: Statistical deviation from historical baseline behavior ($|z| \ge \text{threshold}$).
- **Incident**: Correlated reliability problem representing real or potential operational degradation.

#### Deterministic Correlation Engine
When an anomaly is flagged as `ANOMALOUS`:
1. AURORA identifies the resource.
2. It queries for an existing **active** incident on that resource.
3. If an active incident exists and its latest recorded anomaly is within the configured **correlation window** (default: `300` seconds / 5 minutes), the anomaly is attached as evidence to that incident (with potential severity escalation).
4. Otherwise, a brand new incident is created in `DETECTED` status and initial evidence is attached.

#### Active vs. Terminal Incident States
- **Active States** (eligible for anomaly correlation):
  `DETECTED`, `INVESTIGATING`, `DIAGNOSED`, `RECOVERING`, `VERIFYING`
- **Terminal States** (never receive new anomalies):
  `RESOLVED`, `FAILED`

#### Severity Classification & Escalation
Severity is determined by AURORA policy-based severity mapping from the normalized anomaly magnitude (`anomalyScore`):
- `anomalyScore < 0.5`: `LOW`
- `0.5 <= anomalyScore < 0.8`: `MEDIUM`
- `0.8 <= anomalyScore < 0.95`: `HIGH`
- `anomalyScore >= 0.95`: `CRITICAL`

When a new anomaly is correlated to an existing active incident, the incident's severity is dynamically escalated if the new anomaly possesses higher severity (`CRITICAL > HIGH > MEDIUM > LOW > INFO`).

#### Incident Lifecycle State Machine & Intentional Nulls
- **Deterministic Lifecycle**: Newly created incidents start strictly in `DETECTED` status. State transitions follow a strict deterministic state machine:
  `DETECTED -> INVESTIGATING -> DIAGNOSED -> RECOVERING -> VERIFYING -> RESOLVED (or FAILED from any active state)`.
  Illegal transitions (e.g. `DETECTED -> RESOLVED` or transitions from terminal states) are deterministically rejected with HTTP 409 (`ILLEGAL_STATE`).
- **Root Cause (`rootCause = null`)**: Intentionally left `null` at creation. An anomaly is an observation of a symptom, not a diagnosis of the root cause.
- **Confidence (`confidence = null`)**: Intentionally left `null` at creation. Anomaly scores quantify statistical deviation, not root-cause diagnostic confidence.

#### Evidence Model (`incident_anomaly_evidence`)
Every correlated anomaly is persisted in the `incident_anomaly_evidence` table linked by foreign keys to `incidents` and `resources`:
- `id`: UUID (Primary Key)
- `incident_id`: UUID (Foreign Key referencing `incidents(id)` ON DELETE CASCADE)
- `resource_id`: UUID (Foreign Key referencing `resources(id)` ON DELETE CASCADE)
- `metric_name`: String
- `observed_value`: Double
- `anomaly_score`: Double
- `z_score`: Double (nullable)
- `detection_method`: String (e.g. `Z_SCORE`)
- `observed_at`: Instant (UTC)
- `created_at`: Instant (UTC)

#### Limitations of Deterministic Correlation & Concurrency Model
- **Single-JVM Concurrency Guarantee:** Anomaly correlation concurrency is synchronized per-resource via a bounded, reference-counted in-memory lock registry (`ResourceLockRegistry`). This prevents duplicate conflicting incident creation within a single JVM instance. Multi-instance distributed coordination (e.g. via distributed locks) is NOT guaranteed at this phase.
- **1-Hop Topological Isolation:** Anomaly correlation groups observations per resource. Cross-resource topological investigation is deferred to the deterministic RCA Evidence Engine.

#### Incidents API Endpoints
- `GET /api/v1/incidents` - List incidents (supports filtering via `?resourceId={uuid}`, `?status={status}`, `?severity={severity}`)
- `GET /api/v1/incidents/{id}` - Retrieve specific incident details (returns 404 if not found)
- `GET /api/v1/incidents/{id}/evidence` - Retrieve chronological anomaly evidence associated with an incident (returns 404 if incident not found)
- `PATCH /api/v1/incidents/{id}/status?status={status}` - Transition incident lifecycle status following the state machine (returns 409 on invalid transition)

#### Example: Query Incidents
```bash
# List all active incidents
curl "http://localhost:8080/api/v1/incidents?status=DETECTED"

# Filter by resource ID
curl "http://localhost:8080/api/v1/incidents?resourceId=fd77b4fd-1212-4640-bd75-06b9b6c3f851"
```

#### Example: Query Incident Evidence
```bash
curl http://localhost:8080/api/v1/incidents/4d9f0339-eb05-4b09-b2da-27b2a751c7ac/evidence
```

### 6. RCA Evidence Engine API (Phase 2B)
AURORA provides a deterministic, explainable Root Cause Analysis (RCA) Evidence Engine that investigates incidents using structural dependency relationships, anomaly evidence, historical telemetry, and temporal precedence.

> [!IMPORTANT]
> The RCA Evidence Engine is **completely deterministic** and auditable.
> - **Evidence scores are NOT probabilities** or statistical p-values.
> - **RCA confidence is deterministic and explainable**, derived strictly from evidence completeness and candidate scores.
> - **No machine learning (ML), LLMs, or agentic frameworks** are used in this engine.

#### RCA Investigation Pipeline
```
Incident Detected
   ↓
Collect Incident Evidence & Reference Time
   ↓
Identify Direct Dependencies & Dependents
   ↓
Collect Temporal Anomalies & Telemetry in Lookback Window
   ↓
Generate Candidates (Dependencies, Incident Resource, Dependents)
   ↓
Calculate Deterministic Evidence Scores & Temporal Precedence
   ↓
Rank Candidates Deterministically (Score DESC, Precedence Delta ASC, ID ASC)
   ↓
Generate Explainable RCA Summary & Candidate Explanations
```

#### Deterministic Scoring Formula
Evidence items contribute additively based on concrete observed facts (up to 1.00 max):
- **`ANOMALY` (+0.40)**: Candidate resource exhibited an anomaly within the investigation window (`[detectedAt - lookbackMinutes, detectedAt]`).
- **`TEMPORAL_PRECEDENCE` (+0.25)**: Candidate anomaly occurred strictly *before* the incident anomaly ($t_{candidate} < t_{incident}$). The elapsed time difference is recorded.
- **`DEPENDENCY` (+0.20)**: The investigated resource directly depends on the candidate resource (`investigatedResource DEPENDS_ON candidateResource`).
- **`TELEMETRY_CORRELATION` (+0.15)**: Correlated telemetry moved abnormally or deviated during the incident window.

#### Deterministic Confidence Levels
- `score < 0.30`: **LOW**
- `0.30 <= score < 0.60`: **MODERATE**
- `0.60 <= score < 0.80`: **HIGH**
- `score >= 0.80`: **VERY_HIGH**

#### Insufficient Evidence Behavior
If no candidate receives meaningful supporting evidence (score $\ge 0.30$ or anomalous evidence), the engine assigns status `INSUFFICIENT_EVIDENCE`, sets confidence to `0.0`, and does NOT assign a primary root-cause candidate.

#### RCA Endpoints
- `POST /api/v1/incidents/{incidentId}/rca` - Execute deterministic RCA investigation on an incident (HTTP 201 Created)
- `GET /api/v1/incidents/{incidentId}/rca` - Retrieve latest completed RCA analysis for an incident (HTTP 200 OK, 404 if none)
- `GET /api/v1/incidents/{incidentId}/rca/{analysisId}` - Retrieve specific RCA analysis by ID (HTTP 200 OK, 404 if not found)

#### Example: Execute RCA Analysis
```bash
curl -X POST http://localhost:8080/api/v1/incidents/2223dc6b-e3d0-49bc-8578-9032c6567b4a/rca \
  -H "Content-Type: application/json"
```

**Response (`201 Created`):**
```json
{
  "id": "a19a44f0-42a0-4878-b7c9-cc37f07e8c01",
  "incidentId": "2223dc6b-e3d0-49bc-8578-9032c6567b4a",
  "status": "COMPLETED",
  "investigatedResourceId": "8036dabd-b6e8-412d-81c6-dcd4d86426d6",
  "summary": "RCA analysis completed. Primary candidate root cause: Database connection pool exhaustion on postgres-db-3e5240e7 (evidence score: 0.85, confidence: VERY_HIGH).",
  "confidence": 0.85,
  "confidenceLevel": "VERY_HIGH",
  "startedAt": "2026-09-24T18:31:00Z",
  "completedAt": "2026-09-24T18:31:01Z",
  "createdAt": "2026-09-24T18:31:01Z",
  "candidates": [
    {
      "id": "33b7b4a7-8094-43cb-986c-1793fa7bfb61",
      "analysisId": "a19a44f0-42a0-4878-b7c9-cc37f07e8c01",
      "candidateResourceId": "c67f2b0b-3b65-4cee-afdf-3444843f02bd",
      "candidateMetric": "db_connection_utilization",
      "candidateCause": "Database connection pool exhaustion on postgres-db-3e5240e7",
      "evidenceScore": 0.85,
      "rank": 1,
      "explanation": "postgres-db-3e5240e7 is a candidate root cause because it is a direct dependency of api-service-fae2ff36, exhibited anomalous db_connection_utilization during the investigation window, and its anomaly preceded the incident anomaly by 83 seconds.",
      "primaryCandidate": true,
      "createdAt": "2026-09-24T18:31:01Z",
      "evidence": [
        {
          "id": "e0e2418e-4a6c-4861-bb38-b72e5ea1ee25",
          "candidateId": "33b7b4a7-8094-43cb-986c-1793fa7bfb61",
          "evidenceType": "ANOMALY",
          "resourceId": "c67f2b0b-3b65-4cee-afdf-3444843f02bd",
          "metricName": "db_connection_utilization",
          "observedValue": 97.0,
          "anomalyScore": 0.86,
          "observedAt": "2026-09-24T18:29:27Z",
          "contributionScore": 0.40,
          "explanation": "Candidate resource postgres-db-3e5240e7 exhibited anomalous db_connection_utilization of 97.00 (anomaly score: 0.86) at 2026-09-24T18:29:27Z.",
          "createdAt": "2026-09-24T18:31:01Z"
        },
        {
          "id": "93692d99-5ef2-4fcf-8472-5a21e7ceebf4",
          "candidateId": "33b7b4a7-8094-43cb-986c-1793fa7bfb61",
          "evidenceType": "TEMPORAL_PRECEDENCE",
          "resourceId": "c67f2b0b-3b65-4cee-afdf-3444843f02bd",
          "metricName": "db_connection_utilization",
          "observedValue": 83.0,
          "anomalyScore": null,
          "observedAt": "2026-09-24T18:29:27Z",
          "contributionScore": 0.25,
          "explanation": "postgres-db-3e5240e7 anomaly preceded the incident anomaly by 83 seconds.",
          "createdAt": "2026-09-24T18:31:01Z"
        },
        {
          "id": "8bb38ceb-c564-4bf8-af97-ceab0994fcf0",
          "candidateId": "33b7b4a7-8094-43cb-986c-1793fa7bfb61",
          "evidenceType": "DEPENDENCY",
          "resourceId": "c67f2b0b-3b65-4cee-afdf-3444843f02bd",
          "metricName": null,
          "observedValue": null,
          "anomalyScore": null,
          "observedAt": null,
          "contributionScore": 0.20,
          "explanation": "api-service-fae2ff36 directly depends on postgres-db-3e5240e7 (DEPENDS_ON).",
          "createdAt": "2026-09-24T18:31:01Z"
        }
      ]
    }
  ]
}
```

### 7. Recovery API (Read-Only Proposal Scaffolding)
- `GET /api/v1/incidents/{incidentId}/recovery-plan` - Retrieve proposed mitigation plan and recommended actions (read-only proposal scaffolding; no automated execution or actuation exists)

### 8. Observability & Health
- `GET /actuator/health` - Health indicator (includes database connectivity status)
- `GET /actuator/info` - Application build/environment info
- `GET /actuator/metrics` - JVM & HTTP metrics
