-- Chitthi core schema: document archive + async pipeline bookkeeping.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE document (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     VARCHAR(255) NOT NULL,
    title        VARCHAR(500) NOT NULL,
    language     VARCHAR(20)  NOT NULL,
    status       VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    tags         JSONB        NOT NULL DEFAULT '[]'::jsonb,
    year         INTEGER,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_document_owner ON document (owner_id);

CREATE TABLE page (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id      UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    page_no          INTEGER NOT NULL,
    image_key        VARCHAR(500) NOT NULL,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    original_text    TEXT,
    translated_text  TEXT,
    text_hash        VARCHAR(64),
    edited           BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (document_id, page_no)
);
CREATE INDEX idx_page_document ON page (document_id);

-- tsvector over the English translation; trigram over the original script
-- (Postgres has no Indic stemming, so substring/trigram search stands in).
ALTER TABLE page ADD COLUMN translated_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(translated_text, ''))) STORED;
CREATE INDEX idx_page_translated_tsv ON page USING GIN (translated_tsv);
CREATE INDEX idx_page_original_trgm ON page USING GIN (original_text gin_trgm_ops);

CREATE TABLE ocr_batch (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id   UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    page_range    VARCHAR(50) NOT NULL,
    sarvam_job_id VARCHAR(100),
    status        VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    poll_count    INTEGER NOT NULL DEFAULT 0,
    next_poll_at  TIMESTAMPTZ
);
CREATE INDEX idx_ocr_batch_document ON ocr_batch (document_id);
CREATE INDEX idx_ocr_batch_next_poll ON ocr_batch (next_poll_at) WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE stage_task (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_id          UUID NOT NULL REFERENCES page (id) ON DELETE CASCADE,
    stage            VARCHAR(30) NOT NULL,
    idempotency_key  VARCHAR(200) NOT NULL UNIQUE,
    attempts         INTEGER NOT NULL DEFAULT 0,
    last_error       TEXT,
    status           VARCHAR(30) NOT NULL DEFAULT 'PENDING'
);
CREATE INDEX idx_stage_task_page ON stage_task (page_id);
CREATE INDEX idx_stage_task_status ON stage_task (status);

CREATE TABLE api_call (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    UUID NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    endpoint       VARCHAR(100) NOT NULL,
    units          INTEGER NOT NULL,
    unit_type      VARCHAR(30) NOT NULL,
    latency_ms     INTEGER NOT NULL,
    http_status    INTEGER NOT NULL,
    est_cost_inr   NUMERIC(10, 4) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_api_call_document ON api_call (document_id);
CREATE INDEX idx_api_call_created_at ON api_call (created_at);

CREATE TABLE outbox (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic         VARCHAR(100) NOT NULL,
    payload       JSONB NOT NULL,
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
