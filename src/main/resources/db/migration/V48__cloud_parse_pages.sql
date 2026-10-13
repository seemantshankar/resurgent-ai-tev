CREATE TABLE cloud_parse_page (
    document_version_id INTEGER NOT NULL REFERENCES document_version(document_version_id) ON DELETE CASCADE,
    page_index INTEGER NOT NULL CHECK (page_index > 0),
    parse_job_id TEXT NOT NULL,
    parse_tier TEXT,
    page_markdown TEXT NOT NULL DEFAULT '',
    page_text TEXT NOT NULL DEFAULT '',
    parsed_at TEXT NOT NULL,
    PRIMARY KEY (document_version_id, page_index)
);
CREATE INDEX idx_cloud_parse_page_job_id ON cloud_parse_page(parse_job_id);
