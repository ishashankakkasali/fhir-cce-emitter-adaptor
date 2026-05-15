# Operations Runbook — FHIR CCE Emitter Adaptor

## 1. Prometheus Metrics

All metrics use the prefix `fhir.emitter.` and carry the common tag `application=fhir-cce-emitter-adaptor`.

### Counters

| Metric | Prometheus Name | Tags | Description |
|--------|-----------------|------|-------------|
| `fhir.emitter.callbacks.received` | `fhir_emitter_callbacks_received_total` | `application` | Total callbacks received from the FHIR server |
| `fhir.emitter.forward.success` | `fhir_emitter_forward_success_total` | `application` | Successful forwards to OpenHIM |
| `fhir.emitter.forward.failure` | `fhir_emitter_forward_failure_total` | `application` | Failed forwards (4xx, 5xx, or unreachable) |
| `fhir.emitter.forward.skipped` | `fhir_emitter_forward_skipped_total` | `application` | Forwards skipped — no Patient subject or RelatedPerson reference found (resource cannot be attributed to a patient) |
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

When the emitter restarts, subscriptions are reconciled against the configured resource types. The service bulk-fetches adaptor-owned subscriptions from the FHIR server (by owner tag), creates missing ones, and deletes stale ones.

### Automatic Startup Reconciliation (Recommended)

With `emitter.startup-subscriptions.enabled=true` (the default production configuration), the `StartupSubscriptionRunner` automatically reconciles subscriptions on startup:

1. Sleeps for `delay-seconds` (default 10s) to allow the FHIR server to become ready
2. Bulk-fetches existing **adaptor-owned** subscriptions from the FHIR server by owner tag (`https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`)
3. For each configured resource type: if an adaptor-owned subscription already exists, creation is skipped (`already-exists`); otherwise a new subscription is created (`registered`)
4. Identifies stale subscriptions — adaptor-owned subscriptions on the server whose resource type is no longer in the configured list — and deletes them (`deleted`)
5. Non-adaptor subscriptions (created by other systems) are completely invisible to the reconciliation — never loaded, never modified, never deleted
6. Failures (both creation and deletion) are logged but do not block remaining operations or startup

```bash
# Check startup subscription logs
docker logs fhir-cce-emitter-adaptor | grep "StartupSubscriptionRunner"
```

> **Note:** To change which resource types are subscribed to, update `emitter.startup-subscriptions.resource-types` in YAML or set the `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated). No code changes or rebuild required. On the next restart, the reconciliation will create subscriptions for newly added types and delete stale adaptor-owned subscriptions for removed types.

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

### 4.4 Reference Resolution Failures

**Symptom:** Patient references appear as `"Patient/616"` (numeric ID) instead of `"Patient/NID-..."` in OpenHIM payloads.

**Causes and resolutions:**

1. **Auth failure fetching the FHIR resource** — `ReferenceResolver` uses `FhirClientFactory` with the same `emitter.fhir-server.auth` config as subscription registration. Verify auth is correct (see Section 4.2). Look for FHIR client errors:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "ReferenceResolver"
   ```

2. **Resource has no national identifier** — None of the configured strategies (`use-official`, `type-code`, `system-suffix`) found a match in the resource's `identifier[]` array. Some resources may genuinely not carry a national identifier in the source system. This is expected behavior — unresolvable references are left unchanged and a `WARN` is logged. No action required.

3. **Stale cache after national-id change** — not applicable. `ReferenceResolver` uses a per-request cache (a fresh `HashMap` per inbound callback), so each notification picks up the latest national-id from the FHIR server. The cache only deduplicates lookups within the processing of a single resource notification.
   ```bash
   # Restart the container to clear the reference cache
   docker restart fhir-cce-emitter-adaptor
   ```

4. **Non-resolvable reference type** — Only types listed in `emitter.reference-resolution.resolvable-types` (default: `Patient`) are resolved. References to other types (e.g., `Encounter/123`, `Organization/456`) pass through unchanged. To add a type, set `EMITTER_REFERENCE_RESOLVABLE_TYPES=Patient,Practitioner` (or any comma-separated list) and restart.

5. **Link-follow misconfiguration** — When the national-id lives on a *linked* resource (e.g. SPICE stores the value on `RelatedPerson`, not on `Patient`), configure `EMITTER_REFERENCE_LINK_FOLLOW=Patient:RelatedPerson`. Verify with `DEBUG` logs:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep -E "ReferenceResolver|link"
   ```
   Common issues:
   - The source `Patient` resource has no `link[]` entry pointing at a `RelatedPerson` → resolver falls back to the source's own `identifier[]`, which usually has no national-id either, leaving the reference unchanged. Verify the FHIR data has the expected `Patient.link[].other.reference`.
   - The linked `RelatedPerson` itself has no national-id matching the configured strategies → same fallback. Inspect the linked resource's `identifier[]` directly.
   - Wrong target type in the pair (e.g. `Patient:Person` instead of `Patient:RelatedPerson`) → resolver won't find any matching `link[]` entry.

5. **Wrong match strategy for source server** — The default `use-official,type-code,system-suffix` order works for spec-compliant servers and SPICE. For servers using non-standard or flat identifier systems (e.g., `system: "NID"` with no `use` or `type.coding` fields), override `EMITTER_NATIONAL_ID_SYSTEM_SUFFIX=NID` so the `system-suffix` strategy matches. See [configuration-guide.md](configuration-guide.md#7-reference-resolution-national-id-lookup) for examples.

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

### 4.6 Forwards Skipped (No Patient Context)

**Symptom:** `forward.skipped` counter is incrementing; some resources are not reaching OpenHIM.

**Cause:** The resource has a non-Patient `subject` (e.g. `Group/123`) but no `RelatedPerson` reference in `participant[]` or `performer[]` to resolve a national-id from. The emitter cannot attribute the resource to a patient, so it skips forwarding.

**Resolution:**
1. Check which resources are being skipped:
   ```bash
   docker logs fhir-cce-emitter-adaptor | grep "Skipping forward"
   ```
2. This is **expected behavior** for resources that cannot be linked to a patient. The emitter only forwards resources that can be attributed to a patient (via `subject`, `RelatedPerson` in participants, or identity resources like `Patient`/`Practitioner` that have no subject).
3. If the resource should be forwarded, verify:
   - The resource has a `subject.reference` pointing to a `Patient`
   - OR the resource has a `RelatedPerson` reference in `participant[].individual.reference` or `performer[].reference`
   - OR the resource is an identity resource (Patient, RelatedPerson, Practitioner, etc.) without a `subject` field
4. If the FHIR server data model uses `Group` subjects for community health resources, the `RelatedPerson` participant pattern is the recommended way to establish patient attribution.

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

---

## 8. Future Optimisations

### 8.1 Async Enrichment + Forwarding with Service-Managed Retry

**Current behaviour:**
`SubscriptionCallbackController` delegates to `ForwardingEngine` synchronously on the HAPI FHIR HTTP thread. The controller then returns `200 OK` with an empty body. This design was introduced to prevent HAPI FHIR's `RetryingMessageHandlerWrapper` from triggering an infinite redelivery loop (any non-2xx or non-FHIR body causes HAPI's internal FHIR client to throw `DataFormatException` and retry indefinitely).

The drawback of the current synchronous approach is that the `200 OK` is held until enrichment and forwarding complete. If reference resolution or the OpenHIM POST is slow, the HAPI delivery thread is blocked for the duration. Under high load or a slow FHIR server, this risks exhausting the HTTP thread pool and could exceed HAPI's own delivery timeout — causing a spurious redelivery.

**Proposed improvement — fire-and-forget with bounded thread pool:**

1. The controller hands off the raw JSON to a bounded `ThreadPoolTaskExecutor` and **returns `200 OK` immediately** — HAPI FHIR is ACKed before any enrichment or forwarding begins, completely eliminating the redelivery risk.
2. The async task runs `ResourceEnricher` → `ReferenceResolver` → OpenHIM POST off the HTTP thread.
3. Because the service now controls the retry (HAPI is already ACKed), **exponential backoff retry** can be introduced safely — e.g. up to 3 attempts with `2 s × attempt` backoff — without risking HAPI redelivery loops.
4. Failures after all retries are logged, metered (`forward.failure` counter), and optionally written to a dead-letter log or alert.

**Sketch (Spring `@Async` or explicit `TaskExecutor`):**

```java
// Controller — ACK immediately
@RequestMapping(...)
public ResponseEntity<Void> handleCallback(@PathVariable String callbackKey,
                                           @RequestBody String resourceJson) {
    forwardingEngine.submitAsync(callbackKey, resourceJson);   // non-blocking hand-off
    return ResponseEntity.ok().build();
}

// ForwardingEngine — off-thread with retry
@Async("callbackExecutor")
public void submitAsync(String callbackKey, String resourceJson) {
    String enriched = resourceEnricher.enrichReferences(resourceJson);
    int attempt = 0;
    while (attempt < maxAttempts) {
        try {
            postToOpenhim(enriched, ...);
            forwardSuccessCounter.increment();
            return;
        } catch (Exception e) {
            attempt++;
            if (attempt >= maxAttempts) {
                forwardFailureCounter.increment();
                log.warn("Forward failed after {} attempts: {}", maxAttempts, e.getMessage());
                return;
            }
            Thread.sleep(backoffMs * attempt);   // linear backoff; replace with exponential as needed
        }
    }
}
```

**Configuration additions required:**

```yaml
emitter:
  openhim:
    retry:
      max-attempts: ${OPENHIM_RETRY_MAX_ATTEMPTS:3}
      backoff-ms: ${OPENHIM_RETRY_BACKOFF_MS:2000}

spring:
  task:
    execution:
      pool:
        core-size: 4
        max-size: 16
        queue-capacity: 200
      thread-name-prefix: callback-
```

**New metric to add:** `fhir.emitter.forward.attempts` (distribution summary) — tracks how many attempts each successful forward required, to tune retry parameters in production.

> **Note:** Thread pool sizing should be tuned to the expected callback rate and OpenHIM response latency. A queue-capacity of 200 provides a buffer for bursts; if the queue fills, new callbacks are dropped and `forward.failure` is incremented. Monitor `fhir_emitter_forward_failure_total` and queue depth to detect saturation.
