-- M5-2 (#175): per-mandate data residency and per-page triage routing.
-- TEV_DATA_RESIDENCY=local_only is a process-wide floor over data_residency.
-- V71, V72, and V75 are reserved for other M5 tickets.

CREATE TABLE mandate_policy (
    mandate_id     INTEGER PRIMARY KEY,
    data_residency TEXT NOT NULL DEFAULT 'cloud_allowed'
                   CHECK (data_residency IN ('cloud_allowed', 'local_only')),
    updated_at     TEXT NOT NULL DEFAULT (datetime('now'))
);

ALTER TABLE page ADD COLUMN routing_outcome TEXT
    CHECK (routing_outcome IS NULL OR routing_outcome IN (
        'digital_text', 'local_ocr_kept', 'escalated_cloud',
        'local_ocr_unverified_residency_locked'));

ALTER TABLE page ADD COLUMN triage_reason TEXT;

ALTER TABLE page ADD COLUMN ink_ratio REAL;

ALTER TABLE page ADD COLUMN parse_source TEXT
    CHECK (parse_source IS NULL OR parse_source IN (
        'digital_layout', 'local_ocr', 'llama_parse'));
