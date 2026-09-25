# Chitthi

[![Build](https://github.com/prashant-singh-2001/chitthi-path-1/actions/workflows/ci.yml/badge.svg)](https://github.com/prashant-singh-2001/chitthi-path-1/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)

Turns scanned handwritten letters in Indian scripts into a searchable, translated,
listenable archive — Sarvam Vision (OCR) → Sarvam Translate → Bulbul (TTS),
behind an async, idempotent, resumable pipeline.

Full spec: [`Chitthi — Requirements Document.md`](Chitthi%20—%20Requirements%20Document.md).

## Why this project

The interesting part isn't the AI calls — it's the backend problems around
them: an async job pipeline that's resumable per page, idempotent retries
against rate-limited paid APIs, cost tracking, and full-text search across
scripts Postgres doesn't natively stem.

## Architecture

```mermaid
flowchart LR
    A[Upload API] --> B[(Object storage)]
    A --> C[Job service]
    C --> Q1{{ocr.queue}}
    Q1 --> W1[OCR worker<br/>Sarvam Vision]
    W1 --> Q2{{translate.queue}}
    Q2 --> W2[Translate worker]
    W2 --> Q3{{tts.queue}}
    Q3 --> W3[TTS worker<br/>Bulbul]
    W3 --> D[Assembler<br/>stitch + index]
    D --> E[(Postgres)]
    C -. SSE progress .-> F[Web client]
```

Each worker checks an idempotency key in Postgres before calling Sarvam,
persists the output, and publishes to the next queue via a transactional
outbox — so a retried or duplicated message never triggers a second paid
API call. See the requirements doc's [Architecture and pipeline
design](Chitthi%20—%20Requirements%20Document.md#architecture-and-pipeline-design)
section for the full page state machine.

## Status

- **Day 1–2:** project scaffold, Docker Compose infra, Flyway schema for
  the core pipeline tables, and a `SarvamClient` with a WireMock
  contract test pinning the Digitise job contract.
- **Day 3–4:** `POST /api/documents` and `GET /api/documents/{id}` —
  upload validation, PDF-to-page-image splitting (PDFBox), MinIO-backed
  storage, OCR batching into chunks of 10 pages, a RabbitMQ-backed OCR
  worker, and a scheduled status poller. A 12-page PDF upload now goes
  all the way to 12 pages of transcribed text: it produces two
  `ocr_batch` rows (a 10-page and a 2-page chunk), each submitted to
  Sarvam Document AI Digitise, polled with backoff, and applied back
  onto the `Page` rows as `OCR_DONE` with `original_text` and a
  `text_hash`.
  - The Digitise result ZIP's per-page text field name isn't pinned by
    Sarvam's public docs, so it's read from a configured candidate
    list (`chitthi.ocr.result.text-fields`) with a longest-string
    fallback; `SarvamDigitiseSmokeTest` makes one real, tagged,
    opt-in call to confirm and pin the real field name.
  - Idempotency here is a per-batch atomic claim (an `UPDATE ...
    WHERE status = 'PENDING'`), not yet the `stage_task` idempotency
    key + transactional outbox the requirements doc describes — that,
    along with the Resilience4j rate limiter and dead-letter retry
    queue, is scheduled for Day 8–9.
- **Day 5–6:** translate and TTS stages, sentence chunking, and pure-Java
  WAV stitching. Each page that finishes OCR is translated to English
  (Sarvam Translate, source-language passthrough for English documents),
  then synthesized into audio (Bulbul TTS) — an `en` track always, plus
  an `orig` track when Bulbul supports the document's source language.
  Once every page of a document is terminal, `DocumentAssembler` stitches
  each track's per-page WAVs into one document-level track with
  `javax.sound.sampled` (no FFmpeg) and marks the document `COMPLETE` or
  `PARTIAL`. `GET /api/documents/{id}/audio?lang=orig|en` returns a
  presigned MinIO URL, falling back from `orig` to `en` when the source
  language has no Bulbul coverage. A 12-page Hindi PDF now plays end to
  end in both languages.
  - Also fixed a pre-existing bug from Day 3–4: the OCR listener
    container factory built `SimpleRabbitListenerContainerFactory`
    directly, which silently ignored `spring.rabbitmq.listener.simple.retry`
    — a failing message skipped straight past its 3 configured retries
    to the dead-letter queue. Every stage's factory now goes through
    `SimpleRabbitListenerContainerFactoryConfigurer`.
  - Same idempotency gap as Day 3–4: a redelivered translate/TTS message
    can trigger a duplicate paid call even though the status/hash-guarded
    write keeps the stored result correct. `stage_task` keys and rate
    limiting are still Day 8–9.
- **Day 7:** live progress and a minimal React frontend. Every stage's
  state service (OCR, translate, TTS, assemble, and pipeline failure
  recovery) now publishes a `DocumentProgressEvent` after it commits;
  `GET /api/documents/{id}/events` streams it over Server-Sent Events as
  a full `(document status, every page's status)` snapshot per message,
  so a client that connects late or reconnects mid-pipeline is never
  wrong, and the stream completes itself once the document reaches
  `COMPLETE`/`PARTIAL`. A heartbeat every 15s keeps idle connections
  alive through proxies. The `/frontend` Vite + React + TypeScript app
  uploads a document, opens that stream to show pages advancing live,
  and plays both audio tracks once the document finishes.
  - `SseEmitterRegistry` tracks open connections per document in memory,
    fine for a single instance; several instances would need a shared
    fanout (e.g. one RabbitMQ topic per document) — noted as a
    `TODO(scale)`.

See the [delivery plan](Chitthi%20—%20Requirements%20Document.md#two-week-delivery-plan)
for what's next.

## Prerequisites

- JDK 21+
- Docker Desktop
- Maven
- Node 22+ (for the `/frontend` app)

## Run infra locally

```
docker compose up -d
```

Starts Postgres (`55432` on the host — `5432` is remapped because a
native Postgres install already occupies it on this machine), RabbitMQ
(`5672`, management UI on `15672`), and LocalStack's S3 service (`4566`)
standing in for object storage — MinIO's own images are no longer
freely pullable from either Docker Hub or quay.io as of September 2026,
so `ObjectStorageService`'s MinIO Java client points at LocalStack
instead; it speaks the generic S3 API either way.

## Build and test

```
mvn clean verify
```

Flyway migrates the schema on application startup. Tests use WireMock
for Sarvam contract tests and Testcontainers for Postgres/RabbitMQ/LocalStack
— no real Sarvam API calls happen in the standard test suite. A tagged
smoke test that does make one real, paid Digitise call is excluded by
default; run it deliberately with `mvn test -Dgroups=smoke` once
`SARVAM_API_KEY` is set.

## Run the frontend

```
cd frontend
npm install
npm run dev
```

The dev server proxies `/api` to `http://localhost:8080`, so run the
Spring Boot app (`mvn spring-boot:run`, with infra up and
`SARVAM_API_KEY` set) alongside it. See [`frontend/README.md`](frontend/README.md)
for its own build and test commands.

## Configuration

Set `SARVAM_API_KEY` before running against the real API. See
`src/main/resources/application.yml` for pipeline tuning (chunk sizes,
concurrency, rate limits) mirrored from Sarvam's documented limits.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for local setup and how to
propose changes.

## License

[MIT](LICENSE)
