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

Receives REST-hook notifications from the FHIR server when subscribed resources change. The callback forwards the resource synchronously to OpenHIM and returns the forwarding result — `200 OK` on success, or the error status from OpenHIM on failure.

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

**Response:** `200 OK` on successful forwarding. On forwarding failure, the error status from OpenHIM is propagated (e.g., `400`, `500`) with a structured JSON error body.

> **Note:** If forwarding to OpenHIM fails (after all retries), the error response is propagated back to the FHIR server with a JSON body describing the error. This makes forwarding failures visible to the source system.

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
Content-Type: application/json

{"status": "ok"}
```

**Example Error Response (OpenHIM returned 500):**

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{
  "status": "error",
  "message": "Forwarding to OpenHIM failed",
  "statusCode": 500,
  "detail": "Internal Server Error"
}
```

**Example Error Response (OpenHIM unreachable):**

```http
HTTP/1.1 502 Bad Gateway
Content-Type: application/json

{
  "status": "error",
  "message": "OpenHIM unreachable after 3 attempts",
  "statusCode": 502
}
```

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
| `200 OK` | Callback (success) | Resource forwarded successfully to OpenHIM |
| `200 OK` | Ping (GET/HEAD) | Endpoint verification |
| `4xx` | Callback (forwarding failure) | OpenHIM returned a client error — JSON body: `{"status": "error", "message": "...", "statusCode": 4xx, "detail": "..."}` |
| `5xx` | Callback (forwarding failure) | OpenHIM returned a server error — JSON body: `{"status": "error", "message": "...", "statusCode": 5xx, "detail": "..."}` |
| `502` | Callback (unreachable) | OpenHIM unreachable after all retries — JSON body: `{"status": "error", "message": "...", "statusCode": 502}` |

> **Error responses include a structured JSON body.** On success: `{"status": "ok"}`. On failure (after all retries): `{"status": "error", "message": "...", "statusCode": <code>, "detail": "<OpenHIM response body>"}`. If OpenHIM is unreachable, `detail` is omitted.

---

## 4. Error Handling

### Callback Endpoints (`/callback/**`)

The callback endpoint propagates OpenHIM's error response back to the FHIR server with a structured JSON body:

- **Forwarding succeeds** → `200 OK` with `{"status": "ok"}`
- **Forwarding fails (after all retries)** → error status from OpenHIM (e.g., `400`, `500`) with body:
  ```json
  {"status": "error", "message": "Forwarding to OpenHIM failed", "statusCode": 500, "detail": "<OpenHIM response body>"}
  ```
- **OpenHIM unreachable (after all retries)** → `502 Bad Gateway` with body:
  ```json
  {"status": "error", "message": "OpenHIM unreachable after 3 attempts", "statusCode": 502}
  ```
- **Parse failures** → logged as `WARN`, forwarding still attempted with `resourceType = "Unknown"`

### Error Response Structure

| Field | Type | Always Present | Description |
|-------|------|----------------|-------------|
| `status` | string | Yes | `"ok"` on success, `"error"` on failure |
| `message` | string | Yes (on error) | Human-readable error description |
| `statusCode` | integer | Yes (on error) | HTTP status code from OpenHIM or `502` if unreachable |
| `detail` | string | No | OpenHIM's response body (omitted if unreachable or empty) |

All errors are also logged internally with full context (callbackKey, resourceType, resourceId, HTTP status, response body).

---

## 5. Forwarding Headers

When the `ForwardingEngine` forwards a resource to OpenHIM, it includes these headers:

### Always Present

| Header | Value | Description |
|--------|-------|-------------|
| `Content-Type` | `application/json` | JSON content type |

### Authentication Headers (per target config)

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
