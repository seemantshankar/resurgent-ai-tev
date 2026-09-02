# Keep ingest and redact; do not ask an LLM for region geometry

We tried one-tab LLM enrichment (PR #78, `feat/issue-73-enrichment-report`). Asking the model to invent bounding boxes was slow and non-deterministic: the same prompt produced missing coverage or different rectangles, and live tables were mixed with unused side checks.

**Decision:** the product is `tev-parse ingest` and `tev-parse redact`. LLM region geometry is not merged. A later table-finding pass must be **deterministic Java** and stay small — not the pre-LLM heuristic stack (cost-head rollup, worksheet-role scoring, trusted totals, golden snapshots).

This supersedes “the model proposes region geometry” in ADR 0008 and ADR 0009. Number-redacted export stays: dummy literals, labels and formulas intact, amounts only on the cell graph.
