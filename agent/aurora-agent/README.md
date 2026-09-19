# 🛰️ AURORA Edge Probing Agent

A lightweight, daemonized synthetic canary probing agent designed to be deployed across multiple Kubernetes clusters, edge nodes, and cloud regions.

## Features
- Runs high-frequency HTTP/S, gRPC, and WebSocket synthetic canary transactions.
- Measures DNS lookup time, TLS handshake latency, and time-to-first-byte (TTFB).
- Dispatches telemetry directly to `platform/telemetry-service` or OpenTelemetry collector.
