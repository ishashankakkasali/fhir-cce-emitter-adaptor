# FHIR CCE Emitter Adaptor

A **FHIR-specific Emitter Adaptor** for the Care Coordination Engine (CCE) platform. Subscribes to FHIR R4 resource changes via REST-hook Subscriptions and asynchronously forwards received resources to a configured downstream target.

## Tech Stack

- **Java 21** (LTS)
- **Spring Boot 3.4.x**
- **Gradle 8.x** (Groovy DSL)
- **HAPI FHIR Client 7.4.0** (FHIR R4)

## Quick Start

```bash
# Build
./gradlew build

# Run locally
./gradlew bootRun

# Run with local profile (DEBUG logging, startup subscriptions)
./gradlew bootRun --args='--spring.profiles.active=local'

# Docker
docker compose up --build -d
```

## Configuration

All configuration is injected via environment variables using `${ENV_VAR:default}` syntax. No Docker-specific Spring profile is needed.

See [docs/configuration-guide.md](docs/configuration-guide.md) for full details.

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/architecture.md) | Service design, processing flows, design rationale |
| [API Reference](docs/api-reference.md) | REST API contracts with JSON examples |
| [Configuration Guide](docs/configuration-guide.md) | Properties, profiles, environment variables |
| [Deployment Guide](docs/deployment-guide.md) | Docker, Gradle, deployment instructions |
| [Operations Runbook](docs/operations-runbook.md) | Metrics, health, troubleshooting |

## Port

Default: **9090** (configurable via `SERVER_PORT`)

## License

Copyright © OpenPHC