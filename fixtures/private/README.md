# fixtures/private/

**Do not use this directory for integration testing.** Real-workbook ITs and
manual ingest verification must use the working file under `Project Docs/`
(e.g. `Project Docs/OM Arham Ventures.xlsx`). See
`.cursor/rules/no-fixture-integration.mdc`.

This folder remains gitignored (`fixtures/private/*` with a `!README.md`
exception) for any ad-hoc local scratch copies. Prefer not to keep a second
client FM here — it drifts from Project Docs and caused false bbox/row
mismatches.

## Rules

- Never point `RealWorkbookIT` (or any real-workbook IT) at paths under
  `fixtures/`.
- Never `git add` anything in this directory other than this README.
- Never paste workbook contents, cell dumps, or file paths containing client
  identifiers into commit messages, PR descriptions, or committed test logs.
- For unit-test repros, build a minimal synthetic workbook with Apache POI in
  the test (see `IngestServiceTest`) rather than copying slices of the real
  file.
