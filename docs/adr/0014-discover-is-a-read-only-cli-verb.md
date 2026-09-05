# Region discovery is a read-only `discover` verb, not ingest

The FM Loader remains `tev-parse ingest`: workbook in, cell graph in SQLite, no structural grouping (ADR 0009). Region discovery runs afterwards as `tev-parse discover` in the same binary. It only reads the workspace database and must not reopen the workbook. A discovery failure must not fail or rewrite ingest.

We rejected folding discovery into ingest (one command, but it would make the loader responsible for Candidates) and a separate product/jar (needless split).
