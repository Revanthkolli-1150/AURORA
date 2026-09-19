# AURORA Security & Zero-Trust Architecture

## Security Principles

1. **Mutual TLS (mTLS)**: All inter-service communications between `platform/*` microservices and the `agent/aurora-agent` are authenticated via mTLS.
2. **Role-Based Access Control (RBAC)**: Managed centrally by `platform/identity-service`.
   - `Viewer`: Read-only access to dashboards and SLO telemetry.
   - `Operator`: Capability to execute canaries and acknowledge alerts.
   - `SRE Lead`: Capability to launch and abort chaos resilience drills.
   - `Admin`: Full configuration control and key management.
3. **Safety Interlock Hard-Stops**: Chaos fault injection is cryptographically signed and continuously monitored by independent safety guardrails with hardware/network circuit breakers.
