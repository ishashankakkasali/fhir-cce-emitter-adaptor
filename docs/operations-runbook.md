# Operations Runbook — FHIR CCE Emitter Adaptor

## 1. Prometheus Metrics

All metrics use the prefix `fhir.emitter.` and carry the common tag `application=fhir-cce-emitter-adaptor`.

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.callbacks.received` | `fhir_emitter_callbacks_received_total` | `application` | Total callbacks received from the FHIR server |
| `fhir.emitter.forward.success` | `fhir_emitter_forward_success_total` | `application` | Successful forwards to OpenHIM |
| `fhir.emitter.forward.failure` | `fhir_emitter_forward_failure_total` | `application` | Failed forwards (4xx, 5xx, or unreachable) |
| `fhir.emitter.subscriptions.created` | `fhir_emitter_subscriptions_created_total` | `application` | Subscriptions successfully created on the FHIR server |
| `fhir.emitter.subscriptions.failed` | `fhir_emitter_subscriptions_failed_total` | `application` | Subscription creation failures |
| `fhir.emitter.subscriptions.deleted` | `fhir_emitter_subscriptions_deleted_total` | `application` | Subscriptions successfully deleted |

### Gauges

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.subscriptions.active` | `fhir_emitter_subscriptions_active` | `application` | Current number of active subscriptions (in-memory map size) |

### Timers

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.forward.duration` | `fhir_emitter_forward_duration_seconds` | `application`, `openhim`, `resourceType`, `outcome` | Time to forward a resource to OpenHIM |
| `fhir.emitter.subscription.duration` | `fhir_emitter_subscription_duration_seconds` | `application`, `server`, `operation` | Time to create/delete a subscription |

### Micrometer Naming Convention

Micrometer converts dots to underscores and appends `_total` for counters when exported to Prometheus:

```
fhir.emitter.callbacks.received  → fhir_emitter_callbacks_received_total
fhir.emitter.forward.duration    → fhir_emitter_forward_duration_seconds
```

### Prometheus Scraping

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'fhir-emitter'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['fhir-cce-emitter-adaptor:9090']
```

---

## 2. Health Endpoint Monitoring

### Endpoints

| Endpoint | Purpose | Probe Type |
|----------|---------|------------|
| `/actuator/health` | Overall health (includes custom indicators) | General |
| `/actuator/health/liveness` | JVM is alive | Kubernetes liveness |
| `/actuator/health/readiness` | Ready to serve traffic | Kubernetes readiness |

### Custom Health Indicators

#### FhirServerHealthIndicator

Checks FHIR server connectivity by calling `GET /metadata` (CapabilityStatement):

| Status | Condition | Details |
|--------|-----------|---------|
| **UP** | `/metadata` returns 200 with CapabilityStatement | `serverName`, `url`, `responseTimeMs` |
| **DOWN** | Connection refused, timeout | `serverName`, `url`, `error` |
| **UNKNOWN** | Auth failure (401/403) | `serverName`, `url`, `statusCode` |

#### OpenhimHealthIndicator

Checks OpenHIM connectivity by sending `HEAD` to the base URL:

| Status | Condition | Details |
|--------|-----------|---------|
| **UP** | 2xx/3xx response | `openhimName`, `url`, `responseTimeMs` |
| **DOWN** | Connection refused, timeout | `openhimName`, `url`, `error` |
| **UNKNOWN** | 4xx/5xx response | `openhimName`, `url`, `statusCode` |

### Example Health Response

```json
{
  "status": "UP",
  "components": {
    "fhirServer": {
      "status": "UP",
      "details": {
        "serverName": "default-fhir",
        "url": "http://fhir-server:8090/fhir",
        "responseTimeMs": 45
      }
    },
    "openhim": {
      "status": "UP",
      "details": {
        "openhimName": "openhim",
        "url": "http://openhim:5001/fhir",
        "responseTimeMs": 12
      }
    },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

---

## 3. Subscription Reconciliation After Restart

When the emitter restarts, the in-memory subscription map (`ConcurrentHashMap`) is empty. Subscriptions still exist on the FHIR server but the emitter doesn't know about them locally.

### Automatic Startup Subscription (Recommended)

With `emitter.startup-subscriptions.enabled=true` (the default production configuration), the `StartupSubscriptionRunner` automatically subscribes to all resource types configured in `emitter.startup-subscriptions.resource-types` on startup:

1. Sleeps for `delay-seconds` (default 10s) to allow the FHIR server to become ready
2. Iterates each configured resource type (21 defaults), calling `subscribe()` for each
3. If an **adaptor-owned** subscription already exists on the server for a resource type (detected by matching callback URL prefix), creation is skipped (`already-exists`)
4. Non-adaptor subscriptions (created by other systems) are completely ignored — never modified or deleted
5. New subscriptions are created → `registered`
6. Failures are logged but do not block remaining subscriptions or startup

```bash
# Check startup subscription logs
docker logs fhir-cce-emitter-adaptor | grep "StartupSubscriptionRunner"
```

> **Note:** To change which resource types are subscribed to, update `emitter.startup-subscriptions.resource-types` in YAML or set the `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated). No code changes or rebuild required.

---

## 4. Troubleshooting

### 4.1 Callback URL Not Reachable

**Symptom:** Subscriptions are created successfully but no callbacks are received.

**Cause:** The FHIR server cannot reach `EMITTER_SELF_BASE_URL`.

**Resolution:**
1. Verify the callback URL from the FHIR server's network:
   ```bash
   # From the FHIR server container
   curl -s http://fhir-cce-emitter-adaptor:9090/callback/test
   # Expected: 200 OK
   ```
2. Check `EMITTER_SELF_BASE_URL` — must be the hostname/IP resolvable by the FHIR server, not `localhost`
3. Verify Docker network connectivity (both containers on the same network)
4. Check firewall rules for port 9090

### 4.2 FHIR Server Auth Failure

**Symptom:** Subscribe operations fail with `"failed: 401 Unauthorized"` or `"failed: 403 Forbidden"`.

**Cause:** Invalid credentials or token endpoint misconfiguration.

**Resolution:**
1. Check `FHIR_SERVER_AUTH_TYPE` — is it the correct type for your FHIR server?
2. For `token-endpoint`:
   - Verify `FHIR_SERVER_TOKEN_URL` is reachable from the emitter
   - Check username/password credentials
   - Check the `client` header value — some auth services require a specific value
   - Look for `TokenEndpointAuthService` errors in logs:
     ```bash
     docker logs fhir-cce-emitter-adaptor | grep "TokenEndpointAuth"
     ```
3. For `oauth2`:
   - Verify `FHIR_SERVER_TOKEN_URL` is reachable from the emitter
   - Verify `FHIR_SERVER_OAUTH2_CLIENT_ID` and `FHIR_SERVER_OAUTH2_CLIENT_SECRET`
   - Test the token endpoint manually:
     ```bash
     curl -X POST https://keycloak.example.com/auth/realms/fhir/protocol/openid-connect/token \
       -d "grant_type=client_credentials&client_id=client-id&client_secret=secret" \
       -H "Content-Type: application/x-www-form-urlencoded"
     ```
   - Check if the `scope` is required by your OAuth2 provider
4. For `basic`: Verify `FHIR_SERVER_AUTH_USERNAME` and `FHIR_SERVER_AUTH_PASSWORD`
5. For `bearer`: Verify the token is valid and not expired

### 4.3 OpenHIM Auth Failure

**Symptom:** Callbacks are received (counter increments) but `forward.failure` counter increases.

**Cause:** OpenHIM rejects forwarded requests due to invalid auth.

**Resolution:**
1. Check OpenHIM auth configuration:
   ```bash
   # Verify OpenHIM is reachable
   curl -v http://openhim:5001/fhir
   ```
2. For `basic`: Verify `OPENHIM_AUTH_USERNAME` and `OPENHIM_AUTH_PASSWORD`
3. For `jwt`: Verify `OPENHIM_AUTH_TOKEN` contains a valid JWT
4. For `custom-token`: Verify `OPENHIM_AUTH_TOKEN` matches the Custom Token configured in OpenHIM
5. For `none`: Verify the OpenHIM channel does not require authentication
4. Check `forward.failure` counter for failure patterns

### 4.5 Token Expired

**Symptom:** FHIR operations fail intermittently with 401 errors.

**Cause:** Cached token has expired and the `TokenEndpointAuthService` hasn't refreshed it.

**Resolution:**
1. Check token TTL configuration (default: 3600 seconds)
2. Look for token refresh activity in logs:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "token"
   ```
3. Verify the token endpoint is responding correctly:
   ```bash
   curl -X POST http://token-url/auth/login \
     -d "username=user&password=pass" \
     -H "Content-Type: application/x-www-form-urlencoded"
   ```
4. The service will attempt to refresh on the next request after expiry

---

## 5. Structured Logging

### 3-Tier Configuration (logback-spring.xml)

| Profile | Format | Level | Use Case |
|---------|--------|-------|----------|
| `local` / `default` | Console + MDC fields | `DEBUG` for `org.openphc.cce` | Local development |
| `staging` | Console + MDC fields | `INFO` for all loggers | Pre-production |
| `production` | Structured JSON | `INFO` for `org.openphc.cce` | Log aggregation (ELK, Loki) |

### Log Pattern (Console)

```
%d{ISO8601} [%thread] %-5level %logger{36} [%X{requestId:-}] [%X{callbackKey:-}] [%X{resourceType:-}] [%X{resourceId:-}] - %msg%n
```

### MDC Fields

| Field | Source | Description |
|-------|--------|-------------|
| `requestId` | `X-Request-ID` header or generated UUID | Unique request identifier |
| `callbackKey` | URL path `/callback/{key}/...` | Callback subscription key |
| `resourceType` | Parsed from FHIR JSON | FHIR resource type (e.g., `Patient`) |
| `resourceId` | Parsed from FHIR JSON | FHIR resource ID |
| `method` | HTTP request method | `GET`, `POST`, `PUT`, etc. |
| `path` | HTTP request URI | Request path |

### MDC Lifecycle

- **LoggingFilter** (request thread): Sets `requestId`, `callbackKey`, `method`, `path`; clears ALL in `finally` block
- **ForwardingEngine** (same request thread): Sets `resourceType`, `resourceId` after parsing

### Example Log Output

**Console (local/staging):**
```
2025-01-15T10:30:00.123 [http-nio-9090-exec-1] INFO  ForwardingEngine [abc-123] [patient] [Patient] [456] - Forwarded Patient/456 to openhim (200 OK, 45ms)
```

**JSON (production):**
```json
{
  "timestamp": "2025-01-15T10:30:00.123Z",
  "level": "INFO",
  "logger": "org.openphc.cce.emitter.service.ForwardingEngine",
  "thread": "http-nio-9090-exec-1",
  "message": "Forwarded Patient/456 to openhim (200 OK, 45ms)",
  "requestId": "abc-123",
  "callbackKey": "patient",
  "resourceType": "Patient",
  "resourceId": "456"
}
```

---

## 6. Alerting Recommendations

### Critical Alerts

| Alert | Condition | Action |
|-------|-----------|--------|
| **Forward failure rate high** | `rate(fhir_emitter_forward_failure_total[5m]) > 0.1` | Investigate OpenHIM connectivity |
| **No callbacks received** | `increase(fhir_emitter_callbacks_received_total[15m]) == 0` (when expecting traffic) | Check FHIR server → emitter connectivity |
| **Health DOWN** | `/actuator/health` returns non-UP | Check FHIR server and OpenHIM connectivity |

### Warning Alerts

| Alert | Condition | Action |
|-------|-----------|--------|
| **Subscription failure** | `increase(fhir_emitter_subscriptions_failed_total[5m]) > 0` | Check FHIR server auth and connectivity |

### Grafana Dashboard Panels (Suggested)

| Panel | Metric | Type |
|-------|--------|------|
| Callback Rate | `rate(fhir_emitter_callbacks_received_total[5m])` | Graph |
| Forward Success/Failure | `rate(fhir_emitter_forward_success_total[5m])` vs `rate(fhir_emitter_forward_failure_total[5m])` | Graph |
| Forward Duration (p95) | `histogram_quantile(0.95, fhir_emitter_forward_duration_seconds_bucket)` | Graph |
| Active Subscriptions | `fhir_emitter_subscriptions_active` | Stat |

---

## 7. Operational Procedures

### Force Token Refresh

Currently, tokens are refreshed automatically on TTL expiry or on 401 response from the FHIR server. Manual token refresh is not exposed via API — restart the service to clear the token cache.

### View All Metrics

```bash
curl http://localhost:9090/actuator/prometheus | grep fhir_emitter
```
