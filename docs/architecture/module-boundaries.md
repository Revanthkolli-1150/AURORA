# AURORA Module Boundaries & Interaction Rules

## 1. Modular Monolith Architecture Principles
To ensure that AURORA remains easily maintainable, testable, and ready for future service extraction, strict internal module boundaries are enforced within `platform/aurora-control-plane`.

Every class inside `com.aurora.platform` belongs to an explicit domain module:

```
com.aurora.platform
│
├── common/             <-- Cross-cutting infrastructure (NO business logic)
├── resource/           <-- Infrastructure asset registry & lifecycle
├── telemetry/          <-- Time-series telemetry ingestion & query
├── dependency/         <-- Directed topological dependency graph
├── incident/           <-- Incident state machine, correlation & evidence
├── intelligence/
│   ├── anomaly/        <-- Statistical anomaly detection engines (Phase 1C)
│   ├── rca/            <-- Deterministic root cause analysis engine (Phase 2B)
│   ├── historical/     <-- Set-theoretic historical incident intelligence (Phase 2C)
│   └── graph/          <-- Empirical Bayes dependency edge weighting (Phase 2D-A)
├── policy/             <-- Safety rules and operational policy models
└── recovery/           <-- Future recovery planning & execution (Phase 4/5)
```

---

## 2. Domain Encapsulation Rules

### Rule 1: No Cross-Domain Repository Access
A service in one domain must **never** directly inject or invoke a `JpaRepository` belonging to another domain.
- *Forbidden*: `IncidentCorrelationServiceImpl` injecting `ResourceRepository` directly.
- *Permitted*: `IncidentCorrelationServiceImpl` injecting `ResourceService` to query resource existence or metadata.

### Rule 2: Entities Remain Internal
JPA `@Entity` classes represent the internal persistence schema of a specific domain. They must not be leaked across domain service boundaries or directly exposed in REST API responses where DTO encapsulation is required.
- Cross-domain boundaries communicate using strongly typed DTOs or immutable value objects.

### Rule 3: Zero Circular Module Dependencies
Module dependencies must form a Directed Acyclic Graph (DAG). Circular references between domain modules are strictly prohibited:
```
resource <---------------+
    ^                    |
    |                    |
telemetry                |
    ^                    |
    |                    |
dependency               |
    ^                    |
    |                    |
incident <---------------+
    ^
    |
intelligence (rca, anomaly)
```
- `common` is a foundational leaf dependency: all modules may depend on `common`, but `common` may depend on NO domain modules.
- Domain-specific logic must never be placed in `common`.

### Rule 4: Decoupled Cross-Domain Events
Where asynchronous or decoupled interaction is beneficial (such as anomaly-driven incident creation), components interact via Spring's `ApplicationEventPublisher` or bounded domain services rather than hard-coupling method chains.

---

## 3. Domain Ownership Matrix

| Domain Module | State Owned | Allowed Outbound Dependencies |
| :--- | :--- | :--- |
| `resource` | `resources` table | `common` |
| `telemetry` | `telemetry_events` table | `resource`, `common` |
| `dependency` | `resource_dependencies` table | `resource`, `common` |
| `incident` | `incidents`, `incident_anomaly_evidence` tables | `resource`, `telemetry`, `intelligence.anomaly`, `common` |
| `intelligence.anomaly`| In-memory mathematical computation | `common` |
| `intelligence.rca` | `rca_analyses`, `rca_evidence_items` tables | `incident`, `dependency`, `telemetry`, `common` |
| `intelligence.historical` | In-memory similarity computation | `incident`, `dependency`, `common` |
| `intelligence.graph` | In-memory Empirical Bayes computation | `incident`, `intelligence.rca.application` (port only), `common` |
| `policy` | `policies` table | `common` |
| `common` | None (Stateless infrastructure) | None (Standard libraries only) |
