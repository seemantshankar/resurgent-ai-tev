# Adapt the PostgreSQL semantic schema to SQLite

The parser strategy specifies its schema in PostgreSQL dialect. We decided the parser targets **SQLite** as the operational store (single workspace DB file at a user-supplied path) while keeping the PostgreSQL schema as the **semantic contract**. A fixed compatibility mapping makes the two interchangeable:

```text
JSONB       → TEXT with Jackson validation on write and read
NUMERIC     → NUMERIC (precision/scale enforced in application code)
BIGSERIAL   → INTEGER PRIMARY KEY AUTOINCREMENT
INT[]       → TEXT containing a validated JSON array
BOOLEAN     → INTEGER constrained to 0/1 via CHECK
TIMESTAMPTZ → TEXT containing normalized ISO-8601, UTC preferred
```

`AUTOINCREMENT` is kept deliberately: at this write volume its overhead is negligible and it faithfully preserves `BIGSERIAL`/`SERIAL` never-reuse semantics. Jackson converters and one canonical timestamp formatter are centralized so every repository follows identical rules.

**Consequences**

- Parse-run identity accounts for everything that can change output: `UNIQUE(source_file_id, parser_version, config_hash)` on `parse_run`, `UNIQUE(mandate_id, file_hash)` on `source_file`.
- Persistence is plain JDBC (Xerial SQLite driver) with thin hand-written repositories; versioned SQL migration scripts bundled in the JAR run on DB open.
- If a future deployment needs server PostgreSQL, the semantic contract plus the mapping table define the port — no schema redesign required.
