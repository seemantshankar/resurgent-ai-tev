# fixtures/private/

This directory holds real client financial models used only for local/manual
integration testing. Everything in here except this README is gitignored
(`fixtures/private/*` with a `!README.md` exception) — nothing placed here is
ever committed.

## Placement

Copy the reference client FM to:

```
fixtures/private/OM Arham Ventures.xlsx
```

The corresponding integration test
(`src/test/java/com/resurgent/tev/parser/ingest/RealWorkbookIT.java`) reads
this exact path. When the file is absent — the default for any fresh clone or
CI job without the private fixture provisioned — the test skips cleanly via
`assumeTrue(...)` with a clear message. It only hard-fails in a private CI job
that has been configured to provision the file first.

## Rules

- Never `git add` anything in this directory other than this README.
- Never paste workbook contents, cell dumps, or file paths containing client
  identifiers into commit messages, PR descriptions, or committed test logs.
- If you need a repro case from the real workbook, extract the minimal
  structural shape into a synthetic POI-generated fixture instead (see
  `IngestServiceTest`) rather than committing a slice of the real file.
