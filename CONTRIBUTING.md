# Contributing to Chitthi

Chitthi is primarily a solo portfolio project, but issues, questions and
pull requests are welcome — especially around the pipeline design,
Sarvam API integration, or Indic-script search.

## Getting set up

1. Install JDK 21, Docker Desktop, and Maven.
2. `docker compose up -d` — starts Postgres, RabbitMQ and MinIO.
3. `mvn clean verify` — builds and runs the test suite (WireMock +
   Testcontainers; no real Sarvam API calls happen here).

See [README.md](README.md) for details and current project status.

## Making a change

1. Open an issue first for anything non-trivial, so we can agree on
   the approach before you invest time.
2. Keep commits scoped and logically separate (schema change, feature,
   test, docs) rather than one large diff — it makes review easier and
   matches the project's existing history.
3. Add or update tests for any pipeline behavior you change. The
   idempotency and resumability guarantees in the requirements doc are
   the core value of this project — changes that weaken them need a
   test proving they don't.
4. Run `mvn clean verify` before opening a PR.

## Reporting bugs / requesting features

Use the issue templates — they ask for the minimum context needed to
act on a report (repro steps for bugs, motivation for features).

## Code style

Standard Java conventions, no unusual formatting rules enforced yet.
Favor the patterns already in the codebase (e.g. `SarvamClient` as the
single point of contact with the Sarvam API) over introducing new
abstractions.
