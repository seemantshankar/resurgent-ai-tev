-- #152: one cost column held two meanings — LlamaCloud rows stored credits, every other row stored
-- USD — so any SUM over it was wrong. Label the unit on the row; convert at read time instead.
-- Rows written before this migration are labelled by the vendor that produced them.
ALTER TABLE llm_usage ADD COLUMN cost_unit TEXT NOT NULL DEFAULT 'usd'
    CHECK (cost_unit IN ('usd', 'credits'));
UPDATE llm_usage SET cost_unit = 'credits' WHERE lower(model_id) LIKE 'llama%';
