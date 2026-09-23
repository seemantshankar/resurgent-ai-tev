-- Categories admitted when a packet fits none of the seven seed schedule families.
-- The seed itself is not stored; a name here is offered on later classify runs.

CREATE TABLE schedule_family (
    name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);
