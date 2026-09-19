# ADR-0001: Monorepo Architecture and Technology Foundation

## Status
Accepted

## Date
2026-09-19

## Context
AURORA is designed as a cloud reliability, SLO management, and resilience testing platform. The system needs to coordinate multiple operational modules:
1. Canonical data contracts & types.
2. Background reliability evaluation engine (probes, SLO counters, chaos triggers).
3. Modern real-time operator interface / dashboard.

We require a repository architecture that supports strict type safety across components, rapid local development, shared domain logic, and decoupled deployment targets.

## Decision

1. **Workspace Architecture**:
   - Establish an npm workspace monorepo layout separating `packages/*` (shared domain libraries), `services/*` (backend daemons/engines), and `apps/*` (user-facing applications).
   - Use ES Modules (`"type": "module"`) natively throughout all packages.

2. **Language & Runtime**:
   - Language: **TypeScript 5.x** with strict mode enabled (`noImplicitAny`, `strictNullChecks`, etc.).
   - Runtime: **Node.js 22.x LTS**, taking advantage of native fetch, high-performance crypto, and standard library enhancements.

3. **Domain Types (`packages/types`)**:
   - All external wire protocols, storage entities, and inter-service messages must be typed in `packages/types` to ensure zero drift between the backend engine and dashboard.

4. **Engine Design (`services/engine`)**:
   - Designed around a pluggable worker architecture with asynchronous event loops for probe scheduling and sliding-window SLO calculations.

5. **User Interface (`apps/dashboard`)**:
   - Single Page Application built with Vite and React, engineered for high-frequency telemetry updates with low overhead.

## Consequences

### Positive
- Strict end-to-end type safety between backend engine and UI.
- Direct code sharing without external package publishing overhead.
- Single root `npm install` and synchronized dependency lifecycle.
- Clear separation of concerns and maintainability.

### Negative
- Monorepo tooling requires consistent TypeScript path configurations and clean build order across workspaces.
