# FHIR CCE Emitter Adaptor

A **Spring Boot 3.4 / Java 21** microservice that acts as a **FHIR-specific Emitter Adaptor** for the Care Coordination Engine (CCE) platform. It subscribes to FHIR resource changes on a configured FHIR R4 server (via REST-hook Subscriptions) and forwards received resources to **OpenHIM**, where an OpenHIM Emitter Adaptor (registered as a mediator) wraps and routes events to CCE.

## Position in the CCE Platform

```
FHIR R4 Server (e.g. SPICE HAPI FHIR)
     │  REST-hook Subscription callbacks
     ▼
┌──────────────────────────────────┐
│  ★ FHIR CCE Emitter Adaptor ★   │  ← this service
│  Receive callback → Forward      │
└──────────────┬───────────────────┘
               │  HTTP POST (FHIR JSON)
               ▼
OpenHIM → CCE Collector → Kafka → Compliance
```

The adaptor is deployed on the **source system side**, co-located with the participating system's FHIR server. It captures FHIR resource changes and forwards them as-is — no transformation, no CloudEvents wrapping. That happens downstream in OpenHIM.

## Prerequisites

- Java 21 (Eclipse Temurin recommended)
- Gradle 8.x (wrapper included)
- Docker & Docker Compose (for containerised deployment)

## Quick Start

### Local Development

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

The service starts on port **9090** with DEBUG logging, SSL trust-all enabled, and startup subscriptions active (with 5s delay).

### Docker

```bash
docker compose up --build
```

Builds a multi-stage Docker image (JDK 21 build → JRE 21 runtime) and starts the containerised service.

### Verify

```bash
# Health check
curl http://localhost:9090/actuator/health

# Ping callback endpoint (simulates FHIR server subscription verification)
curl http://localhost:9090/callback/patient
```

## API Endpoints

### Callback Endpoint (`/callback`)

| Method | Path | Description |
|--------|------|-------------|
| `PUT/POST` | `/callback/{resourceType}/**` | REST-hook callback from the FHIR server (consumes `application/json`, `application/fhir+json`) |
| `GET/HEAD` | `/callback/{resourceType}/**` | Ping — FHIR server verifies endpoint before activating subscription |

#### Callback — curl examples (for reference — called by the FHIR server, not operators)

```bash
# Simulate a FHIR callback (POST)
curl -X POST http://localhost:9090/callback/patient \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Patient",
    "id": "123",
    "name": [{"family": "Smith", "given": ["John"]}]
  }'

# Simulate a FHIR callback (PUT with sub-path)
curl -X PUT http://localhost:9090/callback/patient/Patient/123 \
  -H "Content-Type: application/json" \
  -d '{
    "resourceType": "Patient",
    "id": "123",
    "name": [{"family": "Smith", "given": ["John"]}]
  }'

# Ping (GET)
curl http://localhost:9090/callback/patient
```

#### Response Envelopes

Success (forwarding to OpenHIM succeeded):
```json
{"data": {"status": "ok"}}
```

Failure (OpenHIM returned error — status code propagated):
```json
{"error": {"code": "FORWARDING_ERROR", "message": "Forwarding to OpenHIM failed: <OpenHIM response body>"}}
```

Unreachable (OpenHIM unreachable after all retries):
```json
{"error": {"code": "TARGET_UNREACHABLE", "message": "OpenHIM unreachable after 3 attempts"}}
```

### Monitoring Endpoints

| Endpoint | Description |
|----------|-------------|
| `/actuator/health` | Overall health status |
| `/actuator/health/liveness` | Liveness probe (Kubernetes) |
| `/actuator/health/readiness` | Readiness probe (Kubernetes) |
| `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `/actuator/metrics` | Micrometer metrics (JSON) |
| `/actuator/info` | Application info |

## Configuration

All configuration is driven by environment variables with sensible defaults. No separate Docker profile needed.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | `9090` |
| `SHUTDOWN_TIMEOUT` | Graceful shutdown timeout | `30s` |
| `MAX_HTTP_POST_SIZE` | Max callback body size (OOM protection) | `10MB` |
| `EMITTER_SELF_BASE_URL` | Base URL of this service (reachable by FHIR server) | `http://localhost:9090` |
| **FHIR Server** | | |
| `FHIR_SERVER_NAME` | FHIR server display name | `default-fhir` |
| `FHIR_SERVER_BASE_URL` | FHIR server base URL | `http://localhost:8090/fhir` |
| `FHIR_SERVER_AUTH_TYPE` | Auth type: `none`, `basic`, `bearer`, `token-endpoint`, `oauth2` | `token-endpoint` |
| `FHIR_SERVER_TOKEN_URL` | Token endpoint URL (for `token-endpoint` / `oauth2`) | *(empty)* |
| `FHIR_SERVER_AUTH_USERNAME` | Username (for `basic` / `token-endpoint`) | *(empty)* |
| `FHIR_SERVER_AUTH_PASSWORD` | Password (for `basic` / `token-endpoint`) | *(empty)* |
| `FHIR_SERVER_AUTH_CLIENT` | Client type header (for `token-endpoint`) | `web` |
| `FHIR_SERVER_OAUTH2_CLIENT_ID` | OAuth2 client ID (for `oauth2`) | *(empty)* |
| `FHIR_SERVER_OAUTH2_CLIENT_SECRET` | OAuth2 client secret (for `oauth2`) | *(empty)* |
| `FHIR_SERVER_OAUTH2_SCOPE` | OAuth2 scope (for `oauth2`) | *(empty)* |
| `FHIR_SERVER_TOKEN_TTL_SECONDS` | Token cache TTL in seconds | `3600` |
| **OpenHIM** | | |
| `OPENHIM_NAME` | OpenHIM display name | `openhim` |
| `OPENHIM_BASE_URL` | OpenHIM base URL | `http://localhost:5001/fhir` |
| `OPENHIM_AUTH_TYPE` | Auth type: `none`, `basic`, `jwt`, `custom-token` | `basic` |
| `OPENHIM_AUTH_USERNAME` | Username (for `basic`) | *(empty)* |
| `OPENHIM_AUTH_PASSWORD` | Password (for `basic`) | *(empty)* |
| `OPENHIM_AUTH_TOKEN` | Token (for `jwt` / `custom-token`) | *(empty)* |
| `OPENHIM_SSL_TRUST_ALL` | Trust all SSL certs for OpenHIM | `false` |
| `OPENHIM_APPEND_RESOURCE_TYPE` | Append FHIR resource type to URL | `true` |
| `OPENHIM_RETRY_MAX_ATTEMPTS` | Max forward attempts (including first) | `3` |
| `OPENHIM_RETRY_BACKOFF_MS` | Base backoff ms (linear: backoff × attempt) | `2000` |
| **Startup Subscriptions** | | |
| `EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED` | Auto-subscribe on startup | `false` |
| `EMITTER_STARTUP_DELAY_SECONDS` | Delay before subscribing (server readiness) | `10` |
| `EMITTER_STARTUP_FETCH_PAGE_SIZE` | Max existing subscriptions to fetch | `500` |
| `EMITTER_STARTUP_RESOURCE_TYPES` | Comma-separated FHIR resource types | *(21 defaults)* |
| **Observability** | | |
| `HEALTH_SHOW_DETAILS` | Health detail visibility | `when-authorized` |
| `LOG_LEVEL_ROOT` | Root log level | `INFO` |
| `LOG_LEVEL_APP` | Application log level (`org.openphc.cce`) | `INFO` |

### Profiles

| Profile | Purpose | Key overrides |
|---------|---------|---------------|
| `default` | Base config (env-var-wrapped) | All defaults |
| `local` | Local dev | DEBUG logging, SSL trust-all, startup subscriptions on |
| `staging` | Pre-production | INFO logging |
| `production` | Production | WARN root, JSON logging, 5 retries, 45s shutdown, health details hidden |

## Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `fhir_emitter_callbacks_received_total` | Counter | Total callbacks received from FHIR server |
| `fhir_emitter_forward_success_total` | Counter | Successful forwards to OpenHIM |
| `fhir_emitter_forward_failure_total` | Counter | Failed forwards (all retries exhausted) |
| `fhir_emitter_forward_duration_seconds` | Timer | Forwarding duration (tags: `resourceType`, `outcome`) |
| `fhir_emitter_subscriptions_created_total` | Counter | Subscriptions created on FHIR server |
| `fhir_emitter_subscriptions_failed_total` | Counter | Subscription creation failures |
| `fhir_emitter_subscriptions_active` | Gauge | Current active subscriptions |

## Architecture Decisions

- **Stateless** — no database; subscription tracking is in-memory for duplicate detection, reconciled from the FHIR server on startup
- **Synchronous forwarding** — callbacks are forwarded synchronously with linear backoff retry; the controller returns after forwarding completes
- **Server-agnostic** — works with any FHIR R4-compliant server (HAPI FHIR, IBM FHIR, Firely, Google Healthcare API, etc.)
- **OpenHIM-targeted** — forwards FHIR resources as-is to OpenHIM; CloudEvents wrapping happens in the OpenHIM mediator downstream
- **Startup-only subscriptions** — no runtime API for subscribe/unsubscribe; change `resource-types` config and restart
- **Per-connection SSL trust** — trust-all SSL is applied per-RestTemplate, not process-wide
- **Callback body size limited** — 10 MB default via `MAX_HTTP_POST_SIZE` to prevent OOM from oversized payloads

## Testing

```bash
# Run all tests (unit + integration)
./gradlew test

# Run only integration tests
./gradlew test --tests "org.openphc.cce.emitter.integration.*"
```

- **139 tests** total (117 unit + 22 integration)
- Integration tests use **WireMock** (in-process) — no external services required
- No Docker, no database, no special CI/CD configuration needed

## Project Structure

```
src/main/java/org/openphc/cce/emitter/
├── FhirCceEmitterAdaptorApplication.java
├── config/
│   ├── EmitterProperties.java            # @ConfigurationProperties(prefix="emitter")
│   ├── FhirConfig.java                   # FhirContext.forR4() singleton
│   ├── LoggingFilter.java                # MDC request tracing (requestId, resourceType)
│   ├── ObservabilityConfig.java          # Micrometer common tags
│   ├── RestClientConfig.java             # Standard + trust-all RestTemplate beans
│   └── StartupSubscriptionRunner.java    # Auto-subscribe on startup
├── controller/
│   └── SubscriptionCallbackController.java   # /callback/** endpoint
└── service/
    ├── FhirClientFactory.java            # Authenticated HAPI FHIR client creation
    ├── ForwardingEngine.java             # Synchronous forwarding to OpenHIM with retry
    ├── ForwardResult.java                # Forwarding outcome record
    ├── RegistrationResult.java           # Subscription registration outcome record
    ├── SubscriptionRegistrationService.java  # FHIR Subscription CRUD
    └── TokenEndpointAuthService.java     # Token endpoint + OAuth2 token fetching
```
