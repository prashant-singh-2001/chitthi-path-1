# Chitthi

Turns scanned handwritten letters in Indian scripts into a searchable, translated,
listenable archive — Sarvam Vision (OCR) → Sarvam Translate → Bulbul (TTS),
behind an async, idempotent, resumable pipeline.

See `Chitthi — Requirements Document.md` for the full spec.

## Status

Day 1–2 skeleton: project scaffold, Docker Compose infra, Flyway schema,
and a `SarvamClient` with a WireMock contract test pinning the Digitise
job contract. Pipeline workers land in later days per the delivery plan.

## Prerequisites

- JDK 21 (this machine currently only has a JRE 8 install — get a JDK 21
  distribution, e.g. Eclipse Temurin, before building)
- Docker Desktop
- Maven (already available at `apache-maven-3.9.11`)

## Run infra locally

```
docker compose up -d
```

Starts Postgres (`55432` on the host — `5432` is remapped because a
native Postgres install already occupies it on this machine), RabbitMQ
(`5672`, management UI on `15672`), and MinIO (`9000`, console on `9001`).

## Build and test

```
mvn clean verify
```

Flyway migrates the schema on application startup. Tests use WireMock
for Sarvam contract tests and Testcontainers for Postgres/RabbitMQ —
no real Sarvam API calls happen in the standard test suite.

## Configuration

Set `SARVAM_API_KEY` before running against the real API. See
`src/main/resources/application.yml` for pipeline tuning (chunk sizes,
concurrency, rate limits) mirrored from Sarvam's documented limits.
