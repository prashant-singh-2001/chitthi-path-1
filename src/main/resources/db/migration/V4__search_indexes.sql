-- FR9: supports the tag and year filters GET /api/search adds alongside the
-- V1 translated_tsv/original_text trigram indexes.
CREATE INDEX idx_document_tags ON document USING GIN (tags jsonb_path_ops);
CREATE INDEX idx_document_owner_year ON document (owner_id, year);
