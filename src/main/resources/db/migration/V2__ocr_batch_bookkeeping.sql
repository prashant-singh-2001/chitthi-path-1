-- Extends ocr_batch with what the OCR worker and status poller need beyond
-- V1's placeholder columns: a submit-attempt counter separate from the
-- existing poll_count (they fail for different reasons and need separate
-- budgets), an error message to show why a batch gave up, and timestamps.
ALTER TABLE ocr_batch
    ADD COLUMN attempts   INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN last_error TEXT,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- A redelivered upload event must not create a second batch for the same
-- pages, and two batch rows must never claim the same paid Sarvam job. Cheap
-- idempotency guards ahead of the full stage_task/outbox mechanism.
ALTER TABLE ocr_batch
    ADD CONSTRAINT uq_ocr_batch_document_range UNIQUE (document_id, page_range);

CREATE UNIQUE INDEX uq_ocr_batch_sarvam_job
    ON ocr_batch (sarvam_job_id) WHERE sarvam_job_id IS NOT NULL;
