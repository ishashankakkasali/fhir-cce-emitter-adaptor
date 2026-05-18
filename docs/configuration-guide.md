# Configuration Guide — FHIR CCE Emitter Adaptor

## 1. Profile Hierarchy

The service uses Spring Boot profiles to separate environment-specific configuration:

```
application.yml              ← Base config (all values env-var-wrapped with defaults)
  ├── application-local.yml   ← Local development overrides
  ├── application-staging.yml ← Pre-production tuning
  └── application-production.yml ← Production hardening
```

| Profile | Purpose | Key Overrides |
|---------|---------|---------------|
| `default` | Base configuration | All values env-var-wrapped with sensible defaults |
| `local` | Local development | `DEBUG` logging, `ssl-trust-all: true`, `show-details: always`, startup subscriptions enabled |
| `staging` | Pre-production | `INFO` logging, tuned async pool |
| `production` | Production | `WARN` root logging, `show-details: never`, larger pool, more retries |

> **No `docker` profile needed.** The base `application.yml` uses `${ENV_VAR:default}` syntax for all configurable values. Docker/K8s deployments just set environment variables without requiring a separate profile.

---

## 2. Configuration Properties

All configuration is bound to `EmitterProperties` via `@ConfigurationProperties(prefix = "emitter")`.

### Top-Level Structure

```yaml
emitter:
  self-base-url: "..."        # Callback URL base (must be reachable by FHIR server)
  fhir-server:                # Single FHIR R4 server
    name: "..."
    url: "..."
    auth: { ... }
  openhim:                    # OpenHIM connection
    name: "..."
    base-url: "..."
    auth: { ... }             # none, basic, jwt, or custom-token
  startup-subscriptions:      # Auto-subscribe on startup
    enabled: false
    delay-seconds: 10
  reference-resolution:        # Reference resolution — national-id extraction from identity-source resource
    identity-resource-type: "RelatedPerson"    # FHIR resource type used as identity source (RelatedPerson, Patient, etc.)
    national-id-match-strategies: [use-official, type-code, system-suffix]
    national-id-system-suffix: "/national-id"
    national-id-type-code: "NI"
    identity-resource-paths: Encounter:participant.individual.reference,ServiceRequest:performer.reference,Observation:performer.reference   # ResourceType:dot.path entries for locating identity-source references
```

### Config Classes

| Class | Prefix | Description |
|-------|--------|-------------|
| `EmitterProperties` | `emitter` | Root config with `@Valid @NotNull` nested objects |
| `FhirServerConfig` | `emitter.fhir-server` | FHIR server name, URL, auth |
| `FhirServerAuthConfig` | `emitter.fhir-server.auth` | Auth type + credentials for FHIR server |
| `OpenhimConfig` | `emitter.openhim` | OpenHIM name, URL, auth, SSL |
| `OpenhimAuthConfig` | `emitter.openhim.auth` | Auth type + credentials for OpenHIM (none, basic, jwt, or custom-token) |
| `StartupSubscriptionConfig` | `emitter.startup-subscriptions` | Auto-subscribe toggle and delay |
| `ReferenceResolutionConfig` | `emitter.reference-resolution` | Identity-resource type, national-id match strategies, and per-resource-type paths for locating identity-source references |

---

## 3. Base Configuration (`application.yml`)

```yaml
server:
  port: ${SERVER_PORT:9090}
  shutdown: graceful
  servlet:
    context-path: /

spring:
  application:
    name: fhir-cce-emitter-adaptor
  lifecycle:
    timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:30s}

emitter:
  self-base-url: "${EMITTER_SELF_BASE_URL:http://localhost:9090}"

  fhir-server:
    name: "${FHIR_SERVER_NAME:default-fhir}"
    url: "${FHIR_SERVER_BASE_URL:http://localhost:8090/fhir}"
    auth:
      type: "${FHIR_SERVER_AUTH_TYPE:token-endpoint}"      # none | basic | bearer | token-endpoint | oauth2
      token-url: "${FHIR_SERVER_TOKEN_URL:}"
      username: "${FHIR_SERVER_AUTH_USERNAME:}"
      password: "${FHIR_SERVER_AUTH_PASSWORD:}"
      client: "${FHIR_SERVER_AUTH_CLIENT:web}"
      token-cookie-name: "${FHIR_SERVER_TOKEN_COOKIE_NAME:AuthCookie}"
      token-cookie-base64: ${FHIR_SERVER_TOKEN_COOKIE_BASE64:true}
      token-body-field: "${FHIR_SERVER_TOKEN_BODY_FIELD:}"
      # OAuth2 Client Credentials grant
      client-id: "${FHIR_SERVER_OAUTH2_CLIENT_ID:}"
      client-secret: "${FHIR_SERVER_OAUTH2_CLIENT_SECRET:}"
      scope: "${FHIR_SERVER_OAUTH2_SCOPE:}"
      token-ttl-seconds: ${FHIR_SERVER_TOKEN_TTL_SECONDS:3600}

  openhim:
    name: "${OPENHIM_NAME:openhim}"
    base-url: "${OPENHIM_BASE_URL:http://localhost:5001/fhir}"
    auth:
      type: "${OPENHIM_AUTH_TYPE:basic}"                    # none | basic | jwt | custom-token
      username: "${OPENHIM_AUTH_USERNAME:}"
      password: "${OPENHIM_AUTH_PASSWORD:}"
      token: "${OPENHIM_AUTH_TOKEN:}"
    ssl-trust-all: ${OPENHIM_SSL_TRUST_ALL:false}
    append-resource-type: ${OPENHIM_APPEND_RESOURCE_TYPE:true}

  startup-subscriptions:
    enabled: ${EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED:true}
    delay-seconds: ${EMITTER_STARTUP_DELAY_SECONDS:10}
    resource-types: ${EMITTER_STARTUP_RESOURCE_TYPES:Patient,RelatedPerson,Encounter,Observation,Condition,MedicationRequest,MedicationDispense,MedicationStatement,DiagnosticReport,QuestionnaireResponse,ServiceRequest,CarePlan,Appointment,Group,Location,Organization,Practitioner,Coverage,PaymentNotice,Device,Provenance}

  # Reference resolution — national-id extraction from identity-source resource
  reference-resolution:
    identity-resource-type: ${EMITTER_IDENTITY_RESOURCE_TYPE:RelatedPerson}
    national-id-match-strategies: ${EMITTER_NATIONAL_ID_MATCH_STRATEGIES:use-official,type-code,system-suffix}
    national-id-system-suffix: "${EMITTER_NATIONAL_ID_SYSTEM_SUFFIX:/national-id}"
    national-id-type-code: "${EMITTER_NATIONAL_ID_TYPE_CODE:NI}"
    identity-resource-paths: ${EMITTER_IDENTITY_RESOURCE_PATHS:Encounter:participant.individual.reference,ServiceRequest:performer.reference,Observation:performer.reference,Patient:link.other.reference}

logging:
  level:
    root: ${LOG_LEVEL_ROOT:INFO}
    org.openphc.cce: ${LOG_LEVEL_APP:INFO}
    org.springframework.web: ${LOG_LEVEL_SPRING_WEB:INFO}
    ca.uhn.fhir: ${LOG_LEVEL_FHIR:INFO}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: ${HEALTH_SHOW_DETAILS:when-authorized}
      probes:
        enabled: true
  metrics:
    tags:
      application: ${spring.application.name}
```

---

## 4. FHIR Server Configuration

### `emitter.fhir-server`

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `name` | string | Yes | `default-fhir` | Display name used in logs and API responses |
| `url` | string | Yes | `http://localhost:8090/fhir` | FHIR R4 server base URL |
| `auth.type` | string | No | `none` | Auth type: `none`, `basic`, `bearer`, `token-endpoint`, `oauth2` |
| `auth.username` | string | Conditional | — | Username (required for `basic` and `token-endpoint`) |
| `auth.password` | string | Conditional | — | Password (required for `basic` and `token-endpoint`) |
| `auth.token` | string | Conditional | — | Static Bearer token (required for `bearer`) |
| `auth.token-url` | string | Conditional | — | Token endpoint URL (required for `token-endpoint` and `oauth2`) |
| `auth.client` | string | No | `web` | Client type header sent to token endpoint |
| `auth.token-cookie-name` | string | No | `AuthCookie` | Cookie name to extract JWT from Set-Cookie |
| `auth.token-cookie-base64` | boolean | No | `true` | Whether the cookie value is base64-encoded |
| `auth.token-body-field` | string | No | — | JSON field name for token in response body |
| `auth.client-id` | string | Conditional | — | OAuth2 client ID (required for `oauth2`) |
| `auth.client-secret` | string | Conditional | — | OAuth2 client secret (required for `oauth2`) |
| `auth.scope` | string | No | — | OAuth2 scope (optional, space-separated for multiple scopes) |
| `auth.token-ttl-seconds` | long | No | `3600` | Token cache TTL in seconds (overridden by `expires_in` for `oauth2`) |

### FHIR Server Auth Types

#### `none`
No authentication. The FHIR server is accessed without credentials.

#### `basic`
HTTP Basic Auth. Requires `username` and `password`.

```yaml
emitter:
  fhir-server:
    auth:
      type: basic
      username: fhir-user
      password: fhir-pass
```

#### `bearer`
Static Bearer token. Requires `token`.

```yaml
emitter:
  fhir-server:
    auth:
      type: bearer
      token: "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

#### `token-endpoint`
Fetch a token from an HTTP endpoint (e.g., Keycloak, custom auth service). POSTs credentials to `token-url` and extracts the token from the response.

```yaml
emitter:
  fhir-server:
    auth:
      type: token-endpoint
      token-url: "https://keycloak.example.com/auth/realms/fhir/protocol/openid-connect/token"
      username: fhir-user
      password: fhir-pass
      client: web
```

**Token extraction priority:**
1. `Set-Cookie` header — looks for cookie named `token-cookie-name` (default: `AuthCookie`), optionally base64-decodes the value
2. `Authorization` response header — extracts the token value
3. JSON response body — extracts the field named `token-body-field` (e.g., `access_token`, `token`) via Jackson ObjectMapper
4. Raw response body — fallback, treats the entire body as the token

Tokens are cached in-memory with a configurable TTL (default: 3600 seconds). `TokenEndpointAuthService` handles caching and refresh.

#### `oauth2`
Standard OAuth2 Client Credentials grant (`grant_type=client_credentials`). This is the **industry standard** for server-to-server authentication with Keycloak, Azure AD, Google Cloud, Okta, Auth0, and other OAuth2 providers. Requires `token-url`, `client-id`, and `client-secret`.

```yaml
emitter:
  fhir-server:
    auth:
      type: oauth2
      token-url: "https://keycloak.example.com/auth/realms/fhir/protocol/openid-connect/token"
      client-id: fhir-emitter-client
      client-secret: "s3cr3t-k3y"
      scope: "fhir.read"   # optional, space-separated for multiple scopes
```

**How it works:**
1. POSTs to `token-url` with `Content-Type: application/x-www-form-urlencoded`
2. Request body: `grant_type=client_credentials&client_id=...&client_secret=...&scope=...`
3. Extracts `access_token` from the JSON response body:
   ```json
   {
     "access_token": "eyJhbGciOiJSUzI1NiIs...",
     "token_type": "Bearer",
     "expires_in": 3600
   }
   ```
4. If `expires_in` is present in the response, it is used as the token TTL
5. Otherwise, falls back to `token-ttl-seconds` (default: 3600)

Tokens are cached in-memory and automatically refreshed when expired.

> **`token-endpoint` vs `oauth2`:** Use `token-endpoint` for custom auth services (e.g., SPICE) that accept `username`/`password` and return tokens in non-standard formats (cookies, headers). Use `oauth2` for standard OAuth2 providers that support the Client Credentials grant.

---

## 5. OpenHIM Configuration

OpenHIM is the interoperability layer that receives forwarded FHIR resources. An **OpenHIM Emitter Adaptor** (registered as a mediator in OpenHIM) wraps events in CloudEvents envelopes and routes them to CCE.

### `emitter.openhim`

| Property | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `name` | string | Yes | `openhim` | Display name for logs and metrics |
| `base-url` | string | Yes | `http://localhost:5001/fhir` | OpenHIM base URL |
| `auth.type` | string | No | `basic` | Auth type: `none`, `basic`, `jwt`, or `custom-token` |
| `auth.username` | string | Conditional | — | Username (required for `basic`) |
| `auth.password` | string | Conditional | — | Password (required for `basic`) |
| `auth.token` | string | Conditional | — | Token string (required for `jwt` and `custom-token`) |
| `ssl-trust-all` | boolean | No | `false` | Trust all SSL certificates for OpenHIM |
| `append-resource-type` | boolean | No | `true` | Append FHIR resource type to OpenHIM URL |

### OpenHIM URL Construction

When `append-resource-type` is `true` (default), the OpenHIM URL includes the FHIR resource type:

```
Base URL:     http://openhim:5001/fhir
Resource:     Patient
OpenHIM URL:  http://openhim:5001/fhir/Patient
```

When `false`, only the base URL is used:

```
Base URL:     http://openhim:5001/fhir
OpenHIM URL:  http://openhim:5001/fhir
```

### SSL Trust-All

When `ssl-trust-all: true`, the `ForwardingEngine` uses a trust-all `RestTemplate` for OpenHIM. The trust-all SSL context is **per-connection** (not process-wide) — it does not affect FHIR server connections or other outbound traffic.

---

## 6. Startup Auto-Subscription

### `emitter.startup-subscriptions`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable automatic subscription on startup |
| `delay-seconds` | int | `10` | Delay before subscribing (allows FHIR server to become ready) |
| `fetch-page-size` | int | `500` | Maximum number of existing subscriptions to fetch in a single query during reconciliation |

When `enabled: true`, the `StartupSubscriptionRunner` (`ApplicationRunner`, gated by `@ConditionalOnProperty`) reconciles subscriptions on startup: creates missing ones for configured resource types and deletes stale adaptor-owned subscriptions for resource types no longer in the list.

### Resource Types

Resource types for startup subscription are configured in YAML via `emitter.startup-subscriptions.resource-types`. The default list includes 5 FHIR R4 resource types:

```yaml
emitter:
  startup-subscriptions:
    enabled: true
    delay-seconds: 10
    resource-types:
      - Patient
      - RelatedPerson
      - Encounter
      - Observation
      - Condition
      - MedicationRequest
      - MedicationDispense
      - MedicationStatement
      - DiagnosticReport
      - QuestionnaireResponse
      - ServiceRequest
      - CarePlan
      - Appointment
      - Group
      - Location
      - Organization
      - Practitioner
      - Coverage
      - PaymentNotice
      - Device
      - Provenance
```

To customize for a specific deployment, override the list via YAML or the `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated):

```bash
export EMITTER_STARTUP_RESOURCE_TYPES=Patient,Encounter,Observation,ServiceRequest
```

**Add or remove types by editing the configuration** — no code changes or rebuild required. On the next restart, the reconciliation will create subscriptions for newly added types and delete stale adaptor-owned subscriptions for removed types.

### Startup Flow

1. Application starts → `StartupSubscriptionRunner.run()` triggered
2. Sleep for `delay-seconds` (allows FHIR server to become ready)
3. Parse each resource type entry from `emitter.startup-subscriptions.resource-types`
4. Delegate to `registrationService.subscribeAll(resourceTypeEntries)`
5. Bulk-fetch existing adaptor-owned subscriptions from the FHIR server (by owner tag `https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`)
6. For each configured entry: if an adaptor-owned subscription already exists, skip (`already-exists`); otherwise create (`registered`)
7. Identify stale subscriptions — adaptor-owned subscriptions on the server not in the configured list — and delete them (`deleted`)
8. Log per-resource result and summary (N succeeded, M deleted, P failed)

**Failures are non-fatal** — if a subscription fails (FHIR server not ready, resource type not supported), remaining subscriptions continue and the application starts normally.

### Restart Behavior

With `startup-subscriptions.enabled=true`, restarting the emitter automatically reconciles subscriptions:
- **Existing subscriptions** for configured resource types are detected and skipped (`already-exists`)
- **New subscriptions** for resource types added to the config are created (`registered`)
- **Stale subscriptions** for resource types removed from the config are deleted (`deleted`)
- Only adaptor-owned subscriptions (tagged with `https://openphc.org/cce/fhir-emitter|fhir-cce-emitter-adaptor`) are ever loaded or deleted — non-adaptor subscriptions created by other systems are never touched.

---

## 7. Reference Resolution (national-id lookup)

`ReferenceResolver` orchestrates national-id resolution on every inbound FHIR callback, and `ResourceEnricher` sets the resolved value as `subject.reference = "Patient/<national-id>"`.

### Identity Source Type

The **identity-resource type** (default: `RelatedPerson`) is the FHIR resource type from which the national-id is extracted. This is configurable via `emitter.reference-resolution.identity-resource-type` or the `EMITTER_IDENTITY_RESOURCE_TYPE` environment variable. It can be set to `Patient`, `RelatedPerson`, or any resource type that carries a national-id in its `identifier[]`.

**Identity-resource resources (e.g. RelatedPerson):** When the incoming callback is for the configured identity-resource type, the national-id is extracted directly from its own `identifier[]` using the configured match strategies and `subject.reference = "Patient/<national-id>"` is set. Skips forwarding if no national-id is found.

**Other resources (e.g. Encounter, Observation):** The resolver looks up the configured JSON path from `identity-resource-paths` (e.g. `Encounter:participant.individual.reference`), walks the JSON tree along that path to find an identity-source reference (e.g. `RelatedPerson/499063`). The FHIR resource ID extracted from this reference is called the **`personIdentifier`** (e.g. `"499063"` from `"RelatedPerson/499063"`) — it identifies which person resource to fetch. The resolver then fetches `GET /{identityResourceType}/{personIdentifier}?_elements=identifier` from the FHIR server, resolves the national-id via the configured match strategies, and sets `subject.reference = "Patient/<national-id>"`.

**Only `subject.reference` is modified** — all other references in the payload (performer, encounter, practitioner, etc.) are forwarded as-is.

**Strict forwarding rules:** Forwarding is skipped (returns null) if any step fails — no configured path for the resource type, no identity-source reference found at the configured path, or no national-id resolved from the identity-source resource.

### Identity Source Type

| Property | Type | Default | Env Var | Description |
|----------|------|---------|---------|-------------|
| `identity-resource-type` | string | `RelatedPerson` | `EMITTER_IDENTITY_RESOURCE_TYPE` | The FHIR resource type used as the identity source for national-id extraction. Can be `RelatedPerson`, `Patient`, or any resource type that carries a national-id identifier. |

### Related Person Paths

`emitter.reference-resolution.identity-resource-paths` maps FHIR resource types to the JSON path where an identity-source reference (e.g. `RelatedPerson/{personIdentifier}`) can be found. The **`personIdentifier`** is the FHIR resource ID portion extracted from the reference string — it identifies which identity-source resource to fetch from the FHIR server. Format: `ResourceType:dot.separated.path`.

```yaml
emitter:
  reference-resolution:
    identity-resource-paths:
      - "Encounter:participant.individual.reference"
      - "ServiceRequest:performer.reference"
      - "Observation:performer.reference"
      - "Patient:link.other.reference"
```

or via env var (comma-separated):

```bash
EMITTER_IDENTITY_RESOURCE_PATHS=Encounter:participant.individual.reference,ServiceRequest:performer.reference,Observation:performer.reference,Patient:link.other.reference
```

Resources not listed in `identity-resource-paths` will be skipped (not forwarded) unless they are the configured identity-resource type (default: `RelatedPerson`).

### Match Strategies

`emitter.reference-resolution.national-id-match-strategies` is an ordered list. Each
strategy is tried against the resource's `identifier[]` array; the first match wins.

| Strategy | Match rule | When to use |
|----------|-----------|-------------|
| `use-official` | `identifier.use == "official"` | FHIR R4 standard; preferred for spec-compliant servers |
| `type-code` | Any `identifier.type.coding[].code` equals `national-id-type-code` (default `NI`, HL7 v2-0203) | FHIR R4 standard with typed identifiers (e.g. national-id, passport) |
| `system-suffix` | `identifier.system.endsWith(national-id-system-suffix)` (default `/national-id`) | Custom servers like SPICE that don't set `use` or `type.coding` |

Defaults try all three in the order above, so a single deployment can transparently
support a mix of source systems.

### Examples

**SPICE HAPI FHIR (default suffix `/national-id`):**

```jsonc
// RelatedPerson/498113 returned by the FHIR server
{
  "resourceType": "RelatedPerson",
  "id": "498113",
  "identifier": [
    { "system": "http://spice/fhir/identity-type", "value": "National ID" },
    { "system": "http://spice/fhir/national-id",    "value": "NID-1774256338" }
  ]
}
// → subject.reference set to "Patient/NID-1774256338"  (matched by `system-suffix`)
```

No configuration override needed — `system-suffix` matches `…/national-id`.

**Flat identifier systems (no `use` or `type.coding` fields):**

```bash
EMITTER_NATIONAL_ID_MATCH_STRATEGIES=system-suffix
EMITTER_NATIONAL_ID_SYSTEM_SUFFIX=NID
```

**Spec-compliant server with `use=official`:**

```jsonc
{
  "resourceType": "RelatedPerson", "id": "123",
  "identifier": [
    { "use": "secondary", "system": "https://example.org/local",    "value": "L-55" },
    { "use": "official",  "system": "https://example.org/national", "value": "NID-10001" }
  ]
}
// → subject.reference set to "Patient/NID-10001"  (matched by `use-official`, first in the list)
```

### Resolution Behavior

Each inbound callback triggers at most one FHIR server fetch for the identity-source resource (identified by the `personIdentifier` extracted from the reference at the configured path). There is no in-memory caching — every callback resolves fresh from the FHIR server, ensuring the latest data is always used.

### Failure Behavior

`ReferenceResolver` is **strict**: if any step in the resolution pipeline fails — no configured path, no identity-source reference at path (i.e. no `personIdentifier` found), no national-id from the fetched resource, or any exception during resolution — the resolver returns `null`, the enricher passes through the null, and the forward is skipped. This prevents forwarding resources without proper Patient subject context.

---

## 8. Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_PORT` | HTTP server port | `9090` |
| `SHUTDOWN_TIMEOUT` | Graceful shutdown timeout | `30s` |
| `EMITTER_SELF_BASE_URL` | Callback URL base (must be reachable by FHIR server) | `http://localhost:9090` |
| `FHIR_SERVER_NAME` | FHIR server display name | `default-fhir` |
| `FHIR_SERVER_BASE_URL` | FHIR R4 server base URL | `http://localhost:8090/fhir` |
| `FHIR_SERVER_AUTH_TYPE` | FHIR server auth type | `token-endpoint` |
| `FHIR_SERVER_TOKEN_URL` | Token endpoint URL | *(must be set for token-endpoint/oauth2)* |
| `FHIR_SERVER_AUTH_USERNAME` | FHIR server auth username | *(must be set for basic/token-endpoint)* |
| `FHIR_SERVER_AUTH_PASSWORD` | FHIR server auth password | *(must be set for basic/token-endpoint)* |
| `FHIR_SERVER_AUTH_CLIENT` | Client type header for token endpoint | `web` |
| `FHIR_SERVER_TOKEN_COOKIE_NAME` | Cookie name for token extraction | `AuthCookie` |
| `FHIR_SERVER_TOKEN_COOKIE_BASE64` | Whether cookie value is base64 | `true` |
| `FHIR_SERVER_TOKEN_BODY_FIELD` | JSON field for token in response body | *(empty)* |
| `FHIR_SERVER_OAUTH2_CLIENT_ID` | OAuth2 client ID | *(must be set for oauth2)* |
| `FHIR_SERVER_OAUTH2_CLIENT_SECRET` | OAuth2 client secret | *(must be set for oauth2)* |
| `FHIR_SERVER_OAUTH2_SCOPE` | OAuth2 scope (space-separated) | *(empty — optional)* |
| `FHIR_SERVER_TOKEN_TTL_SECONDS` | Token cache TTL in seconds | `3600` |
| `OPENHIM_NAME` | OpenHIM display name | `openhim` |
| `OPENHIM_BASE_URL` | OpenHIM base URL | `http://localhost:5001/fhir` |
| `OPENHIM_AUTH_TYPE` | OpenHIM auth type (`none`, `basic`, `jwt`, or `custom-token`) | `basic` |
| `OPENHIM_AUTH_USERNAME` | OpenHIM auth username | *(must be set for basic)* |
| `OPENHIM_AUTH_PASSWORD` | OpenHIM auth password | *(must be set for basic)* |
| `OPENHIM_AUTH_TOKEN` | OpenHIM auth token (JWT or Custom Token) | *(must be set for jwt/custom-token)* |
| `OPENHIM_SSL_TRUST_ALL` | Trust all SSL for OpenHIM | `false` |
| `OPENHIM_APPEND_RESOURCE_TYPE` | Append resource type to OpenHIM URL | `true` |
| `EMITTER_STARTUP_SUBSCRIPTIONS_ENABLED` | Enable auto-subscribe on startup | `true` |
| `EMITTER_STARTUP_DELAY_SECONDS` | Delay before startup subscriptions | `10` |
| `EMITTER_STARTUP_FETCH_PAGE_SIZE` | Maximum number of existing subscriptions to fetch in a single query | `500` |
| `EMITTER_STARTUP_RESOURCE_TYPES` | Comma-separated FHIR resource types for startup subscription | *(5 defaults — see below)* |
| `EMITTER_IDENTITY_RESOURCE_TYPE` | FHIR resource type used as identity source for national-id extraction (e.g. `RelatedPerson`, `Patient`) | `RelatedPerson` |
| `EMITTER_IDENTITY_RESOURCE_PATHS` | Comma-separated `ResourceType:dot.path` entries for locating identity-source references per resource type. The resolver extracts the `personIdentifier` (FHIR resource ID) from the reference found at the path. | *(4 defaults — see YAML)* |
| `EMITTER_NATIONAL_ID_MATCH_STRATEGIES` | Comma-separated, ordered list of national-id match strategies (`use-official`, `type-code`, `system-suffix`) | `use-official,type-code,system-suffix` |
| `EMITTER_NATIONAL_ID_SYSTEM_SUFFIX` | Suffix to match against `identifier.system` for the `system-suffix` strategy | `/national-id` |
| `EMITTER_NATIONAL_ID_TYPE_CODE` | HL7 v2-0203 code (or other code) used by the `type-code` strategy | `NI` |
| `HEALTH_SHOW_DETAILS` | Health endpoint detail visibility | `when-authorized` |
| `LOG_LEVEL_ROOT` | Root log level | `INFO` |
| `LOG_LEVEL_APP` | Application log level | `INFO` |
| `LOG_LEVEL_SPRING_WEB` | Spring Web log level | `INFO` |
| `LOG_LEVEL_FHIR` | HAPI FHIR log level | `INFO` |

> **Note:** Startup subscription resource types default to 5 FHIR R4 resource types (Patient, RelatedPerson, Encounter, Observation, ServiceRequest). Override via `EMITTER_STARTUP_RESOURCE_TYPES` environment variable (comma-separated) or YAML `emitter.startup-subscriptions.resource-types` list.

---

## 9. Callback URL Reachability

The `self-base-url` property determines the callback URL registered with the FHIR server. The FHIR server must be able to reach this URL to deliver REST-hook notifications.

### Common Scenarios

| Scenario | `self-base-url` value | Notes |
|----------|-----------------------|-------|
| Both in Docker (same network) | `http://fhir-cce-emitter-adaptor:9090` | Use container name |
| Emitter on host, FHIR in Docker | `http://host.docker.internal:9090` | Docker Desktop only |
| Emitter on host, FHIR on host | `http://localhost:9090` | Both on same host |
| Emitter on host, FHIR remote | `http://<emitter-host-ip>:9090` | Use routable IP |
| Kubernetes | `http://fhir-emitter-svc.namespace.svc:9090` | Use K8s service DNS |

> **Common gotcha:** When the FHIR server runs in Docker and the emitter runs on the host (or vice versa), `localhost` won't work. Use the appropriate hostname/IP that is resolvable from the FHIR server's network context.

---

## 10. Profile Examples

### Local Development (`application-local.yml`)

```yaml
emitter:
  openhim:
    ssl-trust-all: true
  startup-subscriptions:
    enabled: true
    delay-seconds: 5

logging:
  level:
    root: DEBUG
    org.openphc.cce: DEBUG

management:
  endpoint:
    health:
      show-details: always
```

### Staging (`application-staging.yml`)

```yaml
logging:
  level:
    root: INFO
    org.openphc.cce: INFO
```

### Production (`application-production.yml`)

```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 45s

logging:
  level:
    root: WARN
    org.openphc.cce: INFO

management:
  endpoint:
    health:
      show-details: never
```
