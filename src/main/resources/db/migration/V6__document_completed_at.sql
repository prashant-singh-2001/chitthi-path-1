-- Day 12 / FR-none: lets AssembleStateService (and OcrResultApplier's early
-- PARTIAL path) record how long a document took end to end, and makes the
-- README's p95 reproducible from the database rather than only from a load
-- test's own stopwatch.
ALTER TABLE document ADD COLUMN completed_at TIMESTAMPTZ;
