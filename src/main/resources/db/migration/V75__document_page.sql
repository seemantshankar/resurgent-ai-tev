-- ADR 0031 / #180: a document is one work, on the file pages that state it.
-- Pages that state no work stay in the file and are not members.
-- A later ticket may attach one page to more than one document.

CREATE TABLE document_page (
    document_id INTEGER NOT NULL REFERENCES document (document_id),
    page_id     INTEGER NOT NULL REFERENCES page (page_id),
    PRIMARY KEY (document_id, page_id)
);

CREATE INDEX idx_document_page_page ON document_page (page_id);
