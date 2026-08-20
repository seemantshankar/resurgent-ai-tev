-- Ticket 04: extend cell table for the XLSX canonical cell contract.
-- BOOLEAN -> INTEGER CHECK(0|1); JSONB -> TEXT with Jackson validation.

ALTER TABLE worksheet ADD COLUMN sheet_state TEXT;

ALTER TABLE cell ADD COLUMN numeric_value NUMERIC;
ALTER TABLE cell ADD COLUMN bool_value INTEGER CHECK (bool_value IN (0, 1));
ALTER TABLE cell ADD COLUMN date_value TEXT;
ALTER TABLE cell ADD COLUMN formula_text TEXT;
ALTER TABLE cell ADD COLUMN formula_normalized TEXT;
ALTER TABLE cell ADD COLUMN formula_state TEXT;
ALTER TABLE cell ADD COLUMN cached_value TEXT;
ALTER TABLE cell ADD COLUMN cache_state TEXT;
ALTER TABLE cell ADD COLUMN coerced_from_text INTEGER NOT NULL DEFAULT 0 CHECK (coerced_from_text IN (0, 1));
ALTER TABLE cell ADD COLUMN parsed_quantity TEXT;
ALTER TABLE cell ADD COLUMN is_error INTEGER NOT NULL DEFAULT 0 CHECK (is_error IN (0, 1));
ALTER TABLE cell ADD COLUMN error_type TEXT;
