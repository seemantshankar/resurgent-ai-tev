-- Shared prerequisite for ticket 02 (Sprint 1 schema) and ticket 03 (CSV sniffer):
-- ingestion metadata such as detected encoding/delimiter lands here as JSON.
ALTER TABLE source_file ADD COLUMN raw_metadata TEXT;
