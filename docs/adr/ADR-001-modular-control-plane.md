# ADR-001: Adoption of Modular Monolith for AURORA Control Plane v0.1

## Status
Accepted

## Date
2026-09-19

## Context
AURORA is an autonomous reliability and self-healing platform engineered to detect anomalies, correlate complex multi-system outages into actionable incidents, and trigger autonomous remediation actions.

During initial scaffolding, a common anti-pattern is premature decomposition into numerous microservices (e.g. separate services for identity, telemetry, policies, incidents, and recovery, bridged by an API gateway). In early development phases, this introduces catastrophic friction:
1. **Unstable Domain Boundaries**: The interaction dynamics between raw telemetry signals, correlated incidents, and remediation workflows are evolving rapidly. Refactoring boundaries across multiple network services requires coordinated cross-repo deployments, wire contract versioning, and complex integration tests.
2. **Operational Overhead**: Running multiple services locally requires orchestration of containers, service discovery, networking meshes, distributed logging, and distributed transactions.
3. **Data Consistency**: Cross-service foreign key integrity and transactional rollbacks cannot be enforced natively without distributed two-phase commit or complex saga orchestrators.
4. **Latency Penalties**: Microservice hops across HTTP/REST add significant networking latency to internal control plane operations.

## Decision
We reject premature microservices for AURORA Control Plane v0.1. We choose to build the system as a **Modular Monolith** using Java 21, Spring Boot 3.3, and PostgreSQL with Flyway migrations.

### Core Architecture Rules:
1. **Single Deployable Unit**: The control plane deploys as a single Spring Boot application (`platform/aurora-control-plane`).
2. **Strict Package Modularity**: Domain boundaries (`resources`, `telemetry`, `incidents`, `recovery`, `policies`) are enforced via Java package encapsulation.
3. **No Cross-Domain Repository Access**: Services must never directly inject or query repositories belonging to another domain. All cross-domain collaboration occurs via clearly defined service interfaces.
4. **Unified Relational Store**: A single PostgreSQL database hosts domain tables with explicit foreign keys (`ON DELETE CASCADE` where applicable) and indexed access paths.
5. **Standardized External Contracts**: REST APIs expose versioned endpoints (`/api/v1/...`) with strict DTO boundaries, Bean Validation, and standardized error schemas.

## Evolution Strategy: The Path to Microservices
We establish a clear criteria for when and how microservices will be extracted:
- **Phase 1 (Current)**: Modular Monolith in Java 21 / Spring Boot 3. Validate domain logic, telemetry ingestion rates, and incident correlation algorithms.
- **Phase 2 (Decoupling)**: Transition inter-module calls to in-memory asynchronous domain events using Spring's `ApplicationEventPublisher`.
- **Phase 3 (Extraction where justified)**: When telemetry throughput exceeds vertical scaling limits, extract `telemetry` into an independently scalable streaming service using Apache Kafka as an event backbone.

## Consequences

### Positive
- **Velocity**: Fast developer inner loop; simple `mvn test` validates the entire control plane in seconds.
- **ACID Guarantees**: Complete transactional consistency across resources, telemetry records, incidents, and recovery actions.
- **Zero Network Latency**: Internal domain communication is lightning-fast in-memory Java method execution.
- **Simplified Operations**: Single Docker container and single PostgreSQL instance in Docker Compose.

### Negative
- Requires discipline to prevent developers from bypassing package encapsulation or directly linking domain entities.
- Monolith scaling scales all domains together until selective microservice extraction is executed.
