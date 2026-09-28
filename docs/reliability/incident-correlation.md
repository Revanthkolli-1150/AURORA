# AURORA Incident Correlation & Lifecycle Intelligence

## 1. Overview
The Incident domain (`com.aurora.platform.incident`) aggregates anomalies into actionable reliability incidents. Rather than flooding operators with individual metric alerts, AURORA correlates related anomalies occurring on the same resource within a sliding temporal window into a single persistent incident record.

---

## 2. Correlation State Machine

### 2.1. Active vs. Terminal Incidents
An incident progresses through explicit lifecycle states:

```
[ DETECTED ] ---> [ INVESTIGATING ] ---> [ DIAGNOSED ] ---> [ RECOVERING ] ---> [ VERIFYING ]
      |                                                                               |
      +---------------------------------> [ RESOLVED ] <------------------------------+
      |                                         ^
      +---------------------------------> [ FAILED ]
```

- **Active States**: `DETECTED`, `INVESTIGATING`, `DIAGNOSED`, `RECOVERING`, `VERIFYING`
- **Terminal States**: `RESOLVED`, `FAILED`

### 2.2. Correlation Rules
When an anomaly is detected on a resource, `IncidentCorrelationServiceImpl` determines whether to correlate with an existing incident or create a new one:

1. **Resource Match**: Must target the exact same `resourceId`.
2. **State Check**: Target incident must be in an **Active** status.
3. **Temporal Proximity**: The duration between the incoming anomaly's timestamp and the incident's `detectedAt` timestamp must be $\le 5\text{ minutes}$ (300 seconds).

If all three conditions hold:
- The anomaly is attached as `IncidentAnomalyEvidence` to the active incident.
- If the incoming anomaly score implies a higher severity than the incident's current severity, the incident's severity is dynamically escalated.
- The incident's `updatedAt` timestamp is refreshed.

If any condition fails:
- A new `Incident` is created in the `DETECTED` status, with the anomaly attached as its initial evidence.

---

## 3. AURORA Policy-Based Severity Mapping
Incident severity is calculated directly from the normalized anomaly magnitude:

| Anomaly Score Range | Mapped Severity | Description |
| :--- | :--- | :--- |
| $\text{score} < 0.50$ | `LOW` | Minor deviation; monitored closely |
| $0.50 \le \text{score} < 0.80$ | `MEDIUM` | Moderate performance degradation |
| $0.80 \le \text{score} < 0.95$ | `HIGH` | Severe anomaly; operator attention required |
| $\text{score} \ge 0.95$ | `CRITICAL` | Extreme statistical departure; immediate threat |

> [!IMPORTANT]
> These severity ranges represent internal **AURORA policy thresholds**. They should not be described as universal standards or "standard SRE severity bands".

---

## 4. Concurrency & Synchronization Model
Concurrent anomaly ingestion for the same resource is coordinated using JVM-local monitor synchronization:

```java
synchronized (resourceId.toString().intern()) {
    // Check active incident, evaluate temporal proximity, attach evidence or create
}
```

### Known Architectural Limitation:
- **JVM-Local Only**: String interning and Java monitors coordinate concurrency across worker threads within a single JVM process.
- **Not Distributed Locking**: This mechanism does **not** provide distributed locking across multi-instance clusters. As documented in [ADR-001](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-001-modular-control-plane.md) and [ADR-003](file:///c:/Users/user/OneDrive/Desktop/AURORA/docs/adr/ADR-003-controlled-future-service-extraction.md), distributed coordination (e.g., Redis Redlock, PostgreSQL advisory locks, or Kafka key-partitioned consumer groups) will be evaluated if and when the control plane is horizontally scaled across multiple nodes.
