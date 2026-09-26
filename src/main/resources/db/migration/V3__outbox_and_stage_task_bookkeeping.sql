-- Extends outbox with what OutboxRelay needs beyond V1's placeholder columns:
-- a creation timestamp to order the sweep and age out published rows, and a
-- retry budget for a publish that fails (broker unreachable, nacked confirm).
ALTER TABLE outbox
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN attempts   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_error TEXT;

-- Extends stage_task with what StageTaskService needs to guard one paid
-- Sarvam call per idempotency key: the call's result (so a DONE row answers
-- with no second call), a lease so a crashed worker's claim can be taken over
-- rather than blocking a page forever, and timestamps.
ALTER TABLE stage_task
    ADD COLUMN result      TEXT,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at  TIMESTAMPTZ NOT NULL DEFAULT now();
