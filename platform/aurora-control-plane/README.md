# AURORA Control Plane (v0.1)

The **AURORA Control Plane** is the foundational brain and system-of-record for AI-Driven Autonomous Reliability & Self-Healing Infrastructure.

It is implemented as a production-grade **modular monolith** in **Java 21** and **Spring Boot 3.3**, backed by **PostgreSQL** and **Flyway** migrations.

---

## Key Features

- **Monitored Resource Registry**: Inventory and tracking of physical servers, containers, pods, databases, and microservices.
- **Telemetry Ingestion Engine**: Structured ingestion and retrieval of time-series infrastructure metrics and logs.
- **Correlated Incident Tracking**: Representation of verified reliability problems with severity, lifecycle statuses, confidence metrics, and root cause analysis.
- **Autonomous Recovery Planning**: Generation and tracking of mitigation plans, risk evaluations, and execution action steps.
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
├── common/             # Tracing filters, Jackson config, global exception handling, standardized API responses
├── resources/          # Resource inventory (controller, service, repository, entity, DTOs)
├── telemetry/          # Telemetry signal ingestion (controller, service, repository, entity, DTOs)
├── incidents/          # Correlated incidents (controller, service, repository, entity, DTOs)
├── recovery/           # Self-healing plans and actions (controller, service, repository, entity, DTOs)
├── policies/           # Reliability thresholds & guardrails (service, repository, entity, DTOs)
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

## API Endpoints

### 1. Resources API
- `POST /api/v1/resources` - Register a monitored resource
- `GET /api/v1/resources` - List all monitored resources
- `GET /api/v1/resources/{id}` - Get resource by UUID

#### Example: Register Resource
```bash
curl -X POST http://localhost:8080/api/v1/resources \
  -H "Content-Type: application/json" \
  -d '{
    "name": "auth-service",
    "type": "SERVICE",
    "status": "HEALTHY",
    "environment": "production",
    "host": "auth-node-01.internal",
    "metadata": { "region": "us-east-1", "version": "1.2.0" }
  }'
```

### 2. Telemetry API
- `POST /api/v1/telemetry` - Ingest telemetry metric
- `GET /api/v1/telemetry/resource/{resourceId}` - Retrieve telemetry for a resource

#### Example: Ingest Telemetry
```bash
curl -X POST http://localhost:8080/api/v1/telemetry \
  -H "Content-Type: application/json" \
  -d '{
    "resourceId": "<RESOURCE_UUID>",
    "type": "METRIC",
    "metricName": "cpu.usage.percent",
    "value": 92.5,
    "unit": "percent",
    "metadata": { "core": 2 }
  }'
```

### 3. Incidents API
- `GET /api/v1/incidents` - List correlated reliability incidents
- `GET /api/v1/incidents/{id}` - Get incident diagnostics and root cause

### 4. Recovery API
- `GET /api/v1/incidents/{incidentId}/recovery-plan` - Get recovery plan and execution actions

### 5. Observability & Health
- `GET /actuator/health` - Health indicator (includes database connectivity status)
- `GET /actuator/info` - Application build/environment info
- `GET /actuator/metrics` - JVM & HTTP metrics
