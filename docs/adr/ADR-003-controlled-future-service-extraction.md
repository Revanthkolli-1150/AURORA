# ADR-003: Controlled Future Service Extraction

## Status
Accepted

## Date
2026-09-26

## Context
AURORA is currently built as a modular monolith within `platform/aurora-control-plane`. As the platform matures and throughput increases, certain capabilities (such as high-frequency telemetry ingestion or long-running root cause analysis workflows) may experience operational constraints that challenge single-process hosting.

However, premature microservice extraction introduces significant network overhead, distributed state management complexity, dual-write consistency problems, and developer velocity penalties. An undisciplined or ad-hoc decomposition into microservices would degrade system reliability rather than enhance it.

## Decision
We establish a **controlled, criteria-driven service extraction protocol**. No domain module may be extracted into an independent microservice unless it satisfies specific, measurable operational triggers.

### Architectural Pre-requisites for Extraction:
1. **Strict Internal Boundaries**: The candidate module must already exist as an isolated domain package (`resource`, `telemetry`, `incident`, `dependency`, `intelligence`, etc.) with zero direct database cross-talk or internal cyclic package dependencies.
2. **Contract-Driven Communication**: Interactions must be mediated via clear DTO contracts and in-memory events (`ApplicationEventPublisher`) before migrating across network transport.
3. **Data Ownership Isolation**: The module must own its schema tables and be capable of operating in a distinct database schema or instance without foreign key constraints crossing service boundaries.

### Extraction Justification Criteria:
A service extraction is only justified when at least one of the following hard criteria is met:
- **Independent Scaling Asymmetry**: The module's resource profile (CPU/memory/IOPS) differs by an order of magnitude from the rest of the application (e.g., telemetry ingestion requiring 50,000 writes/sec vs. incident management handling 50 requests/sec).
- **Independent Deployment Lifecycle**: The domain requires deployment cadence or runtime guarantees that cannot be coupled to the control plane (e.g., edge telemetry agents or rapid ML model version deployments).
- **Fault Isolation**: Failure or memory exhaustion in the module must not cascade to compromise core incident detection or control plane availability.
- **Runtime Environment Divergence**: The module requires specialized hardware or runtimes (e.g., GPU/Python ML execution environments) incompatible with the Java 21 Spring Boot runtime.

## Trade-offs
- **Consequences (Positive)**:
  - Prevents premature distributed system complexity.
  - Maintains fast in-memory execution and transactional consistency while the platform evolves.
  - Ensures that when extraction occurs, boundaries are already clean and well-understood.
- **Consequences (Negative)**:
  - All domains share the same compute resources and deployment pipeline in the interim.
  - Requires continuous enforcement of modular package rules to prevent accidental tight coupling.

## Future Triggers for Revisiting
- **Telemetry Service Extraction**: Triggered when raw telemetry ingestion exceeds 10,000 metrics/second, necessitating an independent streaming ingestion pipeline (e.g., Kafka / streaming worker).
- **Intelligence Service Extraction**: Triggered in Phase 2D/Phase 3 when ML/AI workloads require Python runtimes or asynchronous batch compute resources.
