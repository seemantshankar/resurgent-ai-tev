-- Ticket: error barriers (spec §10.8).
-- Cell-level granularity: a cell is a barrier if ANY function in its formula's
-- function set is one of the error-consuming functions (IFERROR, IFNA, ISERROR,
-- ISNA, ISERR, COUNT, COUNTA, AGGREGATE, SUBTOTAL). This is a simplification of
-- the spec's per-argument barrier semantics, made because the existing schema
-- is cell-granular, not argument-granular.
ALTER TABLE cell ADD COLUMN is_error_barrier INTEGER NOT NULL DEFAULT 0 CHECK (is_error_barrier IN (0, 1));
