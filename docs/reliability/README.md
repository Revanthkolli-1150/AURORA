# AURORA Reliability & SLO Governance Framework

This document outlines the Multi-Window Multi-Burn-Rate (MWMBR) alerting and error budget consumption standards enforced by AURORA.

## Multi-Window Multi-Burn-Rate Alerting

Following the Google SRE Handbook recommendations, alerts are evaluated using two windows (a short window and a long window) to eliminate false positives while guaranteeing rapid alerting on severe burn:

| Window Pair | Burn Rate Threshold | Budget Consumed | Notification Channel |
| :--- | :--- | :--- | :--- |
| **1 hour & 5 minutes** | 14.4x | 2% | Pager (Critical On-Call) |
| **6 hours & 30 minutes** | 6.0x | 5% | Pager (High Priority) |
| **24 hours & 2 hours** | 2.0x | 10% | Ticket / Slack Urgent |
| **3 days & 6 hours** | 1.0x | 10% | Ticket / Next Business Day |

## Error Budget Policies
- **Budget > 20%**: Normal operations, feature deployments permitted.
- **Budget < 20%**: Reliability warning, non-critical canary rollouts restricted.
- **Budget Exhausted (0%)**: Autonomous deployment freeze enforced via `platform/recovery-service`.
