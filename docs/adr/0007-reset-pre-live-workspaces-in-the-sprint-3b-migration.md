# Reset pre-live workspaces in the Sprint 3b migration

Sprint 3b requires a substantially cleaner semantic and review schema, and every existing workspace contains disposable test data because the product is not live. We decided to keep migrations V1–V10 immutable and append a deliberately destructive V11 that resets all parser-owned operational data before creating the target schema, rather than carrying temporary historical shapes and identifiers forward.

**Consequences**

- Existing parse runs, source-file records, cells, regions, provenance, review items, and calculated artifacts are discarded and must be recreated by re-ingestion.
- V11 applies automatically to empty databases but requires explicit CLI opt-in for populated workspaces, showing the exact database path and warning that parser data will be erased.
- The reset is transactional and must pass SQLite foreign-key checks before commit.
- Migration bookkeeping survives, and historical migration files remain unchanged so fresh and upgraded databases converge on the same schema.
