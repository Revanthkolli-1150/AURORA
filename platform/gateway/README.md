# 🚪 AURORA API Gateway

The API Gateway acts as the unified reverse proxy, rate limiter, and security dispatcher for the AURORA platform.

## Responsibilities
- Centralized routing to downstream platform services.
- Token validation and session verification via `platform/identity-service`.
- High-frequency WebSocket event multiplexing for real-time telemetry streaming to `frontend/aurora-console`.
- Rate limiting and request sanitization.
