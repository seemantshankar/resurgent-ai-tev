-- Document Intelligence M3-06: chapter passages and the facts assessed per chapter
-- (spec #133, issue #139). A passage is one heading-delimited chapter of a document
-- version, split from the stored parse output — no re-parse. fact gains an optional
-- passage_id so chapter data points (and quotation-row style lines like the plant and
-- machinery list) can be read per passage. A passage's provenance is a provenance_doc
-- row with entity_type 'passage'; entity_type is free TEXT since V46, so no CHECK to widen.

CREATE TABLE passage (
    passage_id          INTEGER PRIMARY KEY AUTOINCREMENT,
    document_version_id INTEGER NOT NULL REFERENCES document_version (document_version_id),
    passage_index       INTEGER NOT NULL,
    heading             TEXT NOT NULL,
    topic               TEXT,
    page_from           INTEGER NOT NULL,
    page_to             INTEGER NOT NULL,
    summary             TEXT,
    provenance_id       INTEGER REFERENCES provenance_doc (provenance_id),
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_version_id, passage_index)
);

CREATE INDEX idx_passage_document_version ON passage (document_version_id);

ALTER TABLE fact ADD COLUMN passage_id INTEGER REFERENCES passage (passage_id);

CREATE INDEX idx_fact_passage ON fact (passage_id);
