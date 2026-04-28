# API Reference — FHIR CCE Emitter Adaptor

## Overview

The FHIR CCE Emitter Adaptor exposes a single API group — the **Callback API** — which receives REST-hook notifications from the FHIR server. Subscriptions are managed automatically on startup via `StartupSubscriptionRunner`; there is no runtime subscription management API.

| API Group | Base Path | Purpose |
|-----------|-----------|---------|
| **Callback** | `/callback` | REST-hook callback endpoint for the FHIR server |

**Base URL:** `http://{host}:9090` (configurable via `SERVER_PORT`)

---

## 1. Callback API

### 1.1 REST-hook Callback

Receives REST-hook notifications from the FHIR server when subscribed resources change. The callback resolves FHIR internal references to national identifiers (for the configured `resolvable-types`, default `Patient`), enriches the payload, forwards the enriched JSON to OpenHIM, and always returns `200 OK` with an empty body to the FHIR server, regardless of forwarding outcome.

```
PUT /callback/{callbackKey}/**
POST /callback/{callbackKey}/**
```

**Path Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `callbackKey` | string | Yes | Identifier linking the callback to a subscription (typically the lowercase resource type) |

**Wildcard segments** — The FHIR server may append `/{ResourceType}/{id}` to the callback URL. The controller matches all sub-paths:
- `/callback/patient`
- `/callback/patient/Patient/123`
- `/callback/encounter/Encounter/456`

**Content Types:** `application/json`, `application/fhir+json`

**Request Body:** Full FHIR R4 resource JSON

**Response:** Always `200 OK` with empty body, regardless of whether forwarding to OpenHIM succeeded or failed. Forwarding failures are logged and metered but not surfaced to the FHIR server.

> **Why always 200?** HAPI FHIR uses its internal FHIR client to deliver callbacks. Any non-2xx response, or a 2xx response with a non-FHIR body, causes the FHIR client to throw `DataFormatException`, which `RetryingMessageHandlerWrapper` retries indefinitely — creating an infinite redelivery loop. Returning `200 OK` with an empty body prevents this.

**Example Request:**

```http
PUT /callback/patient/Patient/123 HTTP/1.1
Host: localhost:9090
Content-Type: application/fhir+json

{
  "resourceType": "Patient",
  "id": "123",
  "active": true,
  "name": [{"family": "Smith", "given": ["John"]}]
}
```

**Example Response:**

```http
HTTP/1.1 200 OK
```

(Empty body — always returned regardless of forwarding outcome.)

### 1.2 Ping Endpoint

FHIR servers verify the callback endpoint is reachable before activating a subscription. This endpoint handles the ping request.

```
GET /callback/{callbackKey}/**
HEAD /callback/{callbackKey}/**
```

**Response:** `200 OK` with body `"OK"`

> **Note:** GET/HEAD requests do **not** trigger forwarding. They are purely for endpoint verification.

---

## 2. Content Types

| Content Type | Used By | Description |
|-------------|---------|-------------|
| `application/json` | Callback | Standard JSON |
| `application/fhir+json` | Callback | FHIR-specific JSON (preferred by FHIR servers) |

Both content types are accepted on the callback endpoint. The response Content-Type is `application/json`.

---

## 3. HTTP Status Codes

| Status | Endpoint | Meaning |
|--------|----------|---------|
| `200 OK` | Callback (PUT/POST) | Always returned — forwarding success or failure is logged/metered internally |
| `200 OK` | Ping (GET/HEAD) | Endpoint verification |

---

## 4. Error Handling

### Callback Endpoints (`/callback/**`)

The callback endpoint always returns `200 OK` with an empty body. This is required to prevent HAPI FHIR's `RetryingMessageHandlerWrapper` from triggering infinite redelivery loops:

- **Forwarding succeeds** → `200 OK` (empty body), `forward.success` counter incremented
- **Forwarding fails (4xx/5xx from OpenHIM)** → `200 OK` (empty body), logged as `WARN`, `forward.failure` counter incremented
- **OpenHIM unreachable** → `200 OK` (empty body), logged as `WARN`, `forward.failure` counter incremented
- **Parse failures** → logged as `WARN`, forwarding still attempted with `resourceType = "Unknown"`
- **Reference resolution failures** → logged as `WARN` per unresolved reference, original reference value left unchanged, forwarding proceeds

Forwarding failures are observable via Prometheus metrics (`fhir_emitter_forward_failure_total`) and logs. All failures are logged with full context (callbackKey, resourceType, resourceId, HTTP status, response body).

---

## 5. Forwarding Headers

When the `ForwardingEngine` forwards a resource to OpenHIM, it includes these headers:

### Always Present

| Header | Value | Description |
|--------|-------|-------------|
| `Content-Type` | `application/json` | JSON content type |

### Authentication Headers (per OpenHIM config)

| Auth Type | Header | Value |
|-----------|--------|-------|
| `basic` | `Authorization` | `Basic <base64(username:password)>` |
| `jwt` | `Authorization` | `Bearer <jwt-token>` |
| `custom-token` | `Authorization` | `Custom <token>` |
| `none` | — | No auth header |

---

## 6. Subscription Management (Startup-Only)

Subscriptions are **not managed via a runtime API**. Instead, the `StartupSubscriptionRunner` automatically registers FHIR R4 REST-hook Subscriptions on application startup.

### How It Works

1. Application starts → `StartupSubscriptionRunner.run()` triggered (when `emitter.startup-subscriptions.enabled=true`)
2. Sleeps for `delay-seconds` (default 10s) to allow the FHIR server to become ready
3. Iterates each resource type from `emitter.startup-subscriptions.resource-types` (21 defaults)
4. Calls `SubscriptionRegistrationService.subscribe()` for each
5. If an adaptor-owned subscription already exists on the server for a resource type, creation is skipped (`already-exists`). Non-adaptor subscriptions are never touched.
6. Failures are logged but do not block remaining subscriptions or application startup

### Resource Types

21 FHIR resource types are subscribed to on startup by default (configurable via `emitter.startup-subscriptions.resource-types` or `EMITTER_STARTUP_RESOURCE_TYPES` env var):

Patient, RelatedPerson, Encounter, Observation, Condition, MedicationRequest, MedicationDispense, MedicationStatement, DiagnosticReport, QuestionnaireResponse, ServiceRequest, CarePlan, Appointment, Group, Location, Organization, Practitioner, Coverage, PaymentNotice, Device, Provenance

### Restart Behavior

On restart, the `StartupSubscriptionRunner` re-registers subscriptions. If an adaptor-owned subscription already exists on the FHIR server for a resource type, creation is skipped (`already-exists`). Non-adaptor subscriptions are never modified.

### Monitoring Startup Subscriptions

```bash
# Check startup subscription logs
docker logs fhir-cce-emitter-adaptor | grep "StartupSubscriptionRunner"
```
