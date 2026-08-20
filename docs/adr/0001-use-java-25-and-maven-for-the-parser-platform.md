# Use Java 25 and Maven for the parser platform

The parser strategy docs (`Project Docs/parser-strategy.md`, `parser-strategy-v2.md`) were drafted with Python examples (`openpyxl`, `xlrd`, `formulas`). We decided to implement the parser in **Java 25 LTS with Maven** instead, because the product targets external customers including banks standardized on Java Enterprise Edition, and a Java codebase maximizes deployability and maintainability in those environments. Java 25 LTS (released September 2025, supported to 2033) is the current LTS recommended for new 2026–27 production projects.

**Considered Options**

- **Python (as drafted in the strategy docs)**: rejected — deployment and staffing friction in bank/enterprise Java environments.
- **Java core with a Python sidecar for formula evaluation**: deferred, not rejected outright. Sprint 1 is deterministic cell ingestion only and evaluates nothing; cached formula values are used when present. The sidecar-vs-Java-whitelist-evaluator decision is revisited in Sprint 2 with real formula-coverage data.

**Consequences**

- Python library equivalents are replaced: `openpyxl`/`xlrd` → Apache POI 5.5.1 (XSSF for `.xlsx/.xlsm`, HSSF for `.xls`); Python `csv` → FastCSV with an internal deterministic encoding/delimiter sniffer (univocity-parsers was considered and rejected as unmaintained since January 2021).
- Nothing escapes Java in Sprint 1: no Python invocation, no sidecar, no partial Java formula evaluator.
- Single-module Maven project, no framework lock-in (no Spring), plain JDBC persistence, Picocli CLI shipped as a shaded JAR (`tev-parse`).
