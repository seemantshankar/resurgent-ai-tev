-- Document Intelligence M1: identity, versions, layout graph (Architecture §6.2–6.3).
-- Immutable document_version rows + content-addressed blobs on disk; page/block/span/table
-- are the document analog of the Excel cell graph. No meaning tables here (facts/clauses
-- land in later migrations).
-- current_version_id is a soft pointer: document_version already FKs to document, so a hard
-- FK would be circular. Relation resolution prefers this column when set (Architecture §6.2).

CREATE TABLE document (
    document_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id          INTEGER NOT NULL,
    logical_name        TEXT NOT NULL,          -- 'PPA', 'Promoter NW - Sharma'
    doc_family          TEXT NOT NULL,          -- 'agreement'|'certificate'|'dpr'|'quotation'|'technical_report'|'regulatory'|'identity'|'other'
    doc_type_code       TEXT,                   -- pack code when classified (NULL in M1)
    status              TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','withdrawn','replaced','deleted_by_analyst')),
    current_version_id  INTEGER,                -- soft ref → document_version (circular FK avoided)
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE INDEX idx_document_mandate ON document (mandate_id, logical_name);

CREATE TABLE document_version (
    document_version_id INTEGER PRIMARY KEY AUTOINCREMENT,
    document_id         INTEGER NOT NULL REFERENCES document (document_id),
    version_label       TEXT NOT NULL,          -- 'v1', 'Amendment-1', 'signed-2026-03-12'
    file_name           TEXT NOT NULL,
    file_hash           TEXT NOT NULL,          -- sha256 of bytes
    file_ext            TEXT NOT NULL,
    file_role           TEXT NOT NULL,          -- 'fm_workbook'|'dpr'|'agreement'|'certificate'|'quotation'|'technical_report'|'other'
    page_count          INTEGER,
    lang_primary        TEXT,
    ocr_required        INTEGER NOT NULL DEFAULT 0 CHECK (ocr_required IN (0,1)),
    ingest_status       TEXT NOT NULL DEFAULT 'ingested'
                        CHECK (ingest_status IN ('ingested','layout_ok','extracted','failed','rejected')),
    ingested_at         TEXT NOT NULL,
    source_file_id      INTEGER REFERENCES source_file (source_file_id),
    raw_metadata        TEXT,                   -- JSON: PDF producer, image size, etc.
    UNIQUE (document_id, file_hash)
);

CREATE INDEX idx_document_version_hash ON document_version (file_hash);
CREATE INDEX idx_document_version_doc ON document_version (document_id);

CREATE TABLE document_relation (
    relation_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    from_document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    to_document_version_id   INTEGER NOT NULL REFERENCES document_version (document_version_id),
    relation_kind       TEXT NOT NULL CHECK (relation_kind IN (
                            'supersedes','amends','extends','annexes_to','withdraws','duplicate_of','related')),
    note                TEXT,
    created_at          TEXT NOT NULL,
    UNIQUE (from_document_version_id, to_document_version_id, relation_kind)
);

CREATE TABLE page (
    page_id             INTEGER PRIMARY KEY AUTOINCREMENT,
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    page_index          INTEGER NOT NULL,      -- 1-based
    width_px            INTEGER,
    height_px           INTEGER,
    image_path          TEXT,                  -- relative under blobs/{hash}/pages/
    ocr_engine          TEXT,
    ocr_confidence      REAL,
    lang_detected       TEXT,
    UNIQUE (document_version_id, page_index)
);

CREATE TABLE block (
    block_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    page_id             INTEGER NOT NULL REFERENCES page (page_id),
    block_index         INTEGER NOT NULL,      -- reading order on page
    block_kind          TEXT NOT NULL CHECK (block_kind IN (
                            'paragraph','heading','table','list','form_field','footer','header',
                            'stamp','signature','handwriting','figure','unknown')),
    bbox_x0             REAL,
    bbox_y0             REAL,
    bbox_x1             REAL,
    bbox_y1             REAL,
    text_raw            TEXT,                  -- exact text as extracted
    text_norm           TEXT,                  -- whitespace-normalized, no translation
    reading_order       INTEGER,
    is_handwritten      INTEGER NOT NULL DEFAULT 0 CHECK (is_handwritten IN (0,1)),
    ocr_confidence      REAL,
    extractor           TEXT,
    UNIQUE (page_id, block_index)
);

CREATE TABLE span (
    span_id             INTEGER PRIMARY KEY AUTOINCREMENT,
    block_id            INTEGER NOT NULL REFERENCES block (block_id),
    span_index          INTEGER NOT NULL,
    text_raw            TEXT NOT NULL,
    bbox_x0             REAL,
    bbox_y0             REAL,
    bbox_x1             REAL,
    bbox_y1             REAL,
    confidence          REAL,
    UNIQUE (block_id, span_index)
);

CREATE TABLE table_grid (
    table_grid_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    block_id            INTEGER NOT NULL REFERENCES block (block_id),
    n_rows              INTEGER,
    n_cols              INTEGER,
    header_row_count    INTEGER DEFAULT 0
);

CREATE TABLE table_cell (
    table_cell_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    table_grid_id       INTEGER NOT NULL REFERENCES table_grid (table_grid_id),
    row_index           INTEGER NOT NULL,
    col_index           INTEGER NOT NULL,
    text_raw            TEXT,
    bbox_x0             REAL,
    bbox_y0             REAL,
    bbox_x1             REAL,
    bbox_y1             REAL,
    row_span            INTEGER DEFAULT 1,
    col_span            INTEGER DEFAULT 1,
    is_header           INTEGER NOT NULL DEFAULT 0 CHECK (is_header IN (0,1))
);

CREATE TABLE provenance_doc (
    provenance_id       INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type         TEXT NOT NULL,  -- 'fact'|'clause'|'block'|'checklist_result'|'region'|'page'
    entity_id           INTEGER NOT NULL,
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    page_id             INTEGER REFERENCES page (page_id),
    block_id            INTEGER REFERENCES block (block_id),
    span_id             INTEGER REFERENCES span (span_id),
    table_cell_id       INTEGER REFERENCES table_cell (table_cell_id),
    bbox_x0             REAL,
    bbox_y0             REAL,
    bbox_x1             REAL,
    bbox_y1             REAL,
    char_start          INTEGER,
    char_end            INTEGER,
    verbatim            TEXT NOT NULL,          -- exact quote in source language
    quote_hash          TEXT,
    confidence          REAL,
    is_derived          INTEGER NOT NULL DEFAULT 0 CHECK (is_derived IN (0,1)),
    notes               TEXT
);

CREATE INDEX idx_provenance_doc_entity ON provenance_doc (entity_type, entity_id);
CREATE INDEX idx_provenance_doc_version ON provenance_doc (document_version_id);
