-- #151: a stage that ran while the document LLM answered some calls and failed others is
-- neither a clean 'completed' nor a total loss. SQLite cannot widen a CHECK in place, so the
-- table is rebuilt; ids and rows are carried over unchanged because checklist, conflict and
-- skip rows reference assessment_run_id.
-- llm_failures travels beside the status: "why is this run not completed" is a question about
-- the run record, and a column answers it without reading the metrics blob.
CREATE TABLE assessment_run_v67 (
    assessment_run_id INTEGER PRIMARY KEY AUTOINCREMENT,
    mandate_id        INTEGER NOT NULL,
    stage             TEXT NOT NULL CHECK (stage IN (
                          'assess_profile', 'assess_passages', 'assess_clauses',
                          'assess_conflicts', 'assess_checklist')),
    status            TEXT NOT NULL CHECK (status IN (
                          'running', 'completed', 'partial', 'failed')),
    llm_failures      INTEGER NOT NULL DEFAULT 0,
    parse_run_id      INTEGER REFERENCES parse_run (parse_run_id),
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    metrics_json      TEXT,
    UNIQUE (mandate_id, stage)
);
INSERT INTO assessment_run_v67 (assessment_run_id, mandate_id, stage, status, parse_run_id,
        started_at, finished_at, metrics_json)
    SELECT assessment_run_id, mandate_id, stage, status, parse_run_id,
           started_at, finished_at, metrics_json
    FROM assessment_run;
DROP TABLE assessment_run;
ALTER TABLE assessment_run_v67 RENAME TO assessment_run;
