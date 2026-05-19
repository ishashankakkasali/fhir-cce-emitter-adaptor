# Deployment Guide — FHIR CCE Emitter Adaptor

## 1. Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Java (JDK) | 21 (LTS) | Build and local development |
| Gradle | 8.x | Build tool (wrapper included) |
| Docker | 24+ | Container builds |
| Docker Compose | 2.x | Multi-container orchestration |

---

## 2. Building the Service

### Local Build

```bash
# Build (skip tests for speed)
./gradlew build -x test

# Build with tests
./gradlew build

# Run tests only
./gradlew test
```

### Run Locally

```bash
# Default profile
./gradlew bootRun

# Local development profile (DEBUG logging, startup subscriptions enabled)
./gradlew bootRun --args='--spring.profiles.active=local'
```

The service starts on port **9090** (configurable via `SERVER_PORT`).

### Verify

```bash
# Health check
curl http://localhost:9090/actuator/health

# Prometheus metrics
curl http://localhost:9090/actuator/prometheus
```

---

## 3. Docker Multi-Stage Build

### Dockerfile

The Dockerfile uses a multi-stage build for minimal image size:

**Stage 1 — Build (JDK):**
```dockerfile
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN ./gradlew dependencies --no-daemon || true
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test
```

**Stage 2 — Runtime (JRE):**
```dockerfile
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y curl && \
    groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app
COPY --from=build /app/build/libs/fhir-cce-emitter-adaptor-*.jar app.jar
USER appuser
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
```

> **Why `curl`?** The Docker healthcheck uses `curl` to probe `/actuator/health/liveness`. Without it, the container reports as `unhealthy`.

### Build Image

```bash
docker build -t fhir-cce-emitter-adaptor:latest .
```

### Run Container

```bash
docker run -d \
  --name fhir-cce-emitter-adaptor \
  -p 9090:9090 \
  -e FHIR_SERVER_BASE_URL=http://fhir-server:8090/fhir \
  -e FHIR_SERVER_AUTH_TYPE=none \
  -e OPENHIM_BASE_URL=http://openhim:5001/fhir \
  -e EMITTER_SELF_BASE_URL=http://fhir-cce-emitter-adaptor:9090 \
  fhir-cce-emitter-adaptor:latest
```

---

## 4. Docker Compose

### `docker-compose.yml`

```yaml
version: '3.8'

services:
  fhir-cce-emitter-adaptor:
    build: .
    image: fhir-cce-emitter-adaptor:latest
    container_name: fhir-cce-emitter-adaptor
    ports:
      - "9090:9090"
    restart: unless-stopped
    environment:
      # Server
      SERVER_PORT: 9090
      SHUTDOWN_TIMEOUT: 30s

      # Self (callback URL base — must be reachable by FHIR server)
      EMITTER_SELF_BASE_URL: http://fhir-cce-emitter-adaptor:9090

      # FHIR Server
      FHIR_SERVER_NAME: default-fhir
      FHIR_SERVER_BASE_URL: http://fhir-server:8090/fhir
      FHIR_SERVER_AUTH_TYPE: token-endpoint
      FHIR_SERVER_TOKEN_URL: http://fhir-server:8090/auth/login
      FHIR_SERVER_AUTH_USERNAME: fhir-user
      FHIR_SERVER_AUTH_PASSWORD: fhir-pass
      FHIR_SERVER_AUTH_CLIENT: web

      # OpenHIM
      OPENHIM_NAME: openhim
      OPENHIM_BASE_URL: http://openhim:5001/fhir
      OPENHIM_AUTH_TYPE: basic
      OPENHIM_AUTH_USERNAME: fhir-client
      OPENHIM_AUTH_PASSWORD: fhir-secret
      OPENHIM_SSL_TRUST_ALL: "false"
      OPENHIM_APPEND_RESOURCE_TYPE: "true"

      # Startup Subscriptions
      EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED: "false"
      EMITTER_STARTUP_DELAY_SECONDS: 10

      # Observability
      HEALTH_SHOW_DETAILS: when-authorized

    networks:
      - emitter-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9090/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 30s

networks:
  emitter-network:
    external: true
```

### Usage

```bash
# Build and start
docker compose up --build -d

# View logs
docker compose logs -f fhir-cce-emitter-adaptor

# Stop
docker compose down
```

### External Networks

The Docker Compose file references an external network:

| Network | Purpose |
|---------|--------|
| `emitter-network` | Communication with the FHIR server and OpenHIM |

Create it before starting:

```bash
docker network create emitter-network
```

---

## 5. Environment Variable Injection

The service does **not** require a Docker-specific Spring profile. All configuration is injected via environment variables using the `${ENV_VAR:default}` syntax in `application.yml`.

### Minimal configuration for Docker:

```bash
# Required — must be reachable by the FHIR server
EMITTER_SELF_BASE_URL=http://fhir-cce-emitter-adaptor:9090

# FHIR server connection
FHIR_SERVER_BASE_URL=http://fhir-server:8090/fhir
FHIR_SERVER_AUTH_TYPE=none    # or basic, bearer, token-endpoint, oauth2

# OpenHIM connection
OPENHIM_BASE_URL=http://openhim:5001/fhir
OPENHIM_AUTH_TYPE=basic         # or none, jwt, custom-token
OPENHIM_AUTH_USERNAME=fhir-client
OPENHIM_AUTH_PASSWORD=fhir-secret
```

### Kubernetes ConfigMap/Secret:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: fhir-emitter-config
data:
  SERVER_PORT: "9090"
  EMITTER_SELF_BASE_URL: "http://fhir-emitter-svc.cce.svc:9090"
  FHIR_SERVER_NAME: "default-fhir"
  FHIR_SERVER_BASE_URL: "http://fhir-server-svc.fhir.svc:8090/fhir"
  FHIR_SERVER_AUTH_TYPE: "token-endpoint"
  OPENHIM_NAME: "openhim"
  OPENHIM_BASE_URL: "http://openhim-svc.interop.svc:5001/fhir"
  OPENHIM_AUTH_TYPE: "basic"
  EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED: "true"
  EMITTER_STARTUP_DELAY_SECONDS: "15"
  EMITTER_PERSON_IDENTITY_RESOURCE_TYPE: "RelatedPerson"   # or "Patient" — the FHIR resource type from which national-id is extracted
---
apiVersion: v1
kind: Secret
metadata:
  name: fhir-emitter-secrets
type: Opaque
stringData:
  FHIR_SERVER_TOKEN_URL: "http://fhir-server-svc.fhir.svc:8090/auth/login"
  FHIR_SERVER_AUTH_USERNAME: "fhir-user"
  FHIR_SERVER_AUTH_PASSWORD: "fhir-pass"
  OPENHIM_AUTH_USERNAME: "fhir-client"
  OPENHIM_AUTH_PASSWORD: "fhir-secret"
```

---

## 6. Callback URL Reachability

The most critical deployment consideration is ensuring the FHIR server can reach the emitter's callback URL.

### How Callbacks Work

1. The emitter registers a `Subscription` on the FHIR server with `channel.endpoint = {self-base-url}/callback/{resourceType}`
2. When a subscribed resource changes, the FHIR server sends an HTTP PUT/POST to that endpoint
3. If the endpoint is unreachable, the FHIR server cannot deliver callbacks

### Reachability Matrix

| FHIR Server Location | Emitter Location | `EMITTER_SELF_BASE_URL` |
|-------------------|-----------------|-----------------------|
| Docker container | Same Docker network | `http://fhir-cce-emitter-adaptor:9090` |
| Docker container | Host machine | `http://host.docker.internal:9090` (Docker Desktop only) |
| Host machine | Host machine | `http://localhost:9090` |
| Remote server | Any | `http://<emitter-routable-ip>:9090` |
| Kubernetes | Kubernetes | `http://fhir-emitter-svc.namespace.svc:9090` |

### Verification

After subscribing, verify the FHIR server can reach the callback:

```bash
# From the FHIR server's network context:
curl -s http://fhir-cce-emitter-adaptor:9090/callback/test
# Expected: 200 OK "OK"
```

---

## 7. Graceful Shutdown

The service supports graceful shutdown to allow in-flight forwards to complete:

| Property | Default | Production | Description |
|----------|---------|------------|-------------|
| `server.shutdown` | `graceful` | `graceful` | Enable graceful shutdown |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | `45s` | Max time to wait for in-flight requests |

### Shutdown Sequence

1. Stop accepting new HTTP requests
2. Wait for in-flight HTTP requests to complete (up to `timeout-per-shutdown-phase`)
3. Shut down

---

## 8. Health Checks

### Endpoints

| Endpoint | Purpose | Use Case |
|----------|---------|----------|
| `/actuator/health` | Overall health status | General monitoring |
| `/actuator/health/liveness` | JVM is running | Kubernetes liveness probe |
| `/actuator/health/readiness` | Ready to serve traffic | Kubernetes readiness probe |
| `/actuator/prometheus` | Prometheus metrics | Metrics scraping |
| `/actuator/metrics` | Micrometer metrics (JSON) | Ad-hoc metrics queries |
| `/actuator/info` | Application information | Deployment verification |

### Custom Health Indicators

| Indicator | Checks | UP | DOWN | UNKNOWN |
|-----------|--------|----|----|---------|
| `FhirServerHealthIndicator` | `GET /metadata` on FHIR server | Response received | Connection refused / timeout | Auth failure (401/403) |
| `OpenhimHealthIndicator` | `HEAD` on OpenHIM base URL | 2xx/3xx response | Connection refused / timeout | 4xx/5xx response |

### Docker Compose Health Check

```yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:9090/actuator/health/liveness"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 30s
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 9090
  initialDelaySeconds: 30
  periodSeconds: 30
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 9090
  initialDelaySeconds: 15
  periodSeconds: 10
```

---

## 9. Resource Allocation

### Minimum Requirements

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| Memory | 128 MB heap | 256 MB heap |
| CPU | 0.5 vCPU | 1 vCPU |
| Disk | Negligible | Negligible |

The service is lightweight — it does not persist data, does not run a database, and forwards resources as-is without transformation.

### JVM Tuning

```bash
# In Docker — set JVM heap
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]
```

### Scaling

The service is designed for **single-instance deployment**. In-memory subscription tracking (`ConcurrentHashMap`) is not shared across instances. If high-availability is needed, run behind a load balancer with sticky sessions for the callback URL.

---

## 10. Network Requirements

### Inbound

| From | To | Port | Purpose |
|------|----|------|---------|
| FHIR server | Emitter | 9090 | REST-hook subscription callbacks |
| Operator/Automation | Emitter | 9090 | Subscription management API |
| Monitoring | Emitter | 9090 | Health checks and Prometheus scraping |

### Outbound

| From | To | Port | Purpose |
|------|----|------|---------|
| Emitter | FHIR server | varies | FHIR API (Subscription CRUD, /metadata) |
| Emitter | OpenHIM | varies | Forward FHIR resources to OpenHIM |
| Emitter | Token endpoint | varies | Token-endpoint auth (if configured) |

---

## 11. Deployment Checklist

- [ ] **Callback URL reachable** — `EMITTER_SELF_BASE_URL` is resolvable from the FHIR server's network
- [ ] **FHIR server accessible** — `FHIR_SERVER_BASE_URL` is reachable from the emitter
- [ ] **OpenHIM accessible** — `OPENHIM_BASE_URL` (OpenHIM) is reachable from the emitter
- [ ] **Auth configured** — FHIR server and OpenHIM credentials are correct
- [ ] **Token endpoint accessible** — If using `token-endpoint` auth, the token URL is reachable
- [ ] **Health check passing** — `/actuator/health` returns `UP`
- [ ] **Startup subscriptions** — Set `EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED=true` if auto-subscribe is desired
- [ ] **SSL trust** — Set `OPENHIM_SSL_TRUST_ALL=true` only if OpenHIM uses self-signed certs
- [ ] **Logging level** — Use `INFO` or `WARN` for production (not `DEBUG`)
- [ ] **Docker networks** — External network created (`emitter-network`)
- [ ] **Port exposed** — Port 9090 is accessible for callbacks
