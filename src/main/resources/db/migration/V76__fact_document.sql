-- A fact belongs to the document it was read from. Several documents may share
-- one file version, so the version's own document is not the fact's document.

ALTER TABLE fact ADD COLUMN document_id INTEGER REFERENCES document (document_id);

UPDATE fact
SET document_id = (
    SELECT dv.document_id FROM document_version dv
    WHERE dv.document_version_id = fact.document_version_id
)
WHERE document_id IS NULL;

CREATE INDEX idx_fact_document ON fact (document_id);
