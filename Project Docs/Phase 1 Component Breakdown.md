# Phase 1 — Atomic Component Breakdown

**Project:** TEV Report Generation & Workflow Automation
**Scope:** Phase 1 — Client FM Validation Against Supporting Documents
**Source:** `TEV Automation BRD.md` (v1.2)
**Purpose:** Individually testable components for project scaffolding.

---

## 1. Ingestion Layer

- **Spreadsheet Parser** — Parses client FM (`.xlsx`, `.xls`, `.csv`) into a normalized internal structure: sheets, cell coordinates, values, formulas, merged cells.
  - *Test:* fixture files → assert parsed structure, values, and formula preservation.
- **Document Text/Table Extractor** — Extracts raw text and tables from PDFs/scanned docs (quotations, DPR, audited statements, certificates), retaining page numbers for traceability.
  - *Test:* sample PDFs → assert extracted text/tables and correct page references.
- **Document Type Classifier** — Labels each uploaded file as quotation / DPR / balance sheet / P&L / net worth certificate / sanction letter / other.
  - *Test:* labeled fixture set → classification accuracy.

## 2. Extraction Layer (per document type)

Each extractor takes parsed document content → returns a typed structured record. Each is testable with fixture documents.

- **Quotation Item Extractor** — line items with description, quantity, unit rate, amount, taxes/duties, freight, installation, currency, validity, vendor.
- **Audited Financials Extractor** — turnover, net worth, existing debt, cost lines from balance sheets & P&L across years.
- **Net Worth Certificate Extractor** — promoter/director net worth figures.
- **Sanction Letter Extractor** — debt amount, tenor, interest rate, moratorium, working capital limits.
- **DPR Fact Extractor** — project description, location, capacity, implementation schedule, cost heads, means of finance.
- **FM Structure Extractor** — project-cost heads, historical schedules, liabilities, capacity/revenue start assumptions from the normalized FM structure.

## 3. Mapping & Classification Layer

- **Cost-Head Classifier** — Maps each quotation item to the applicable project-cost head/sub-head.
  - *Test:* item fixtures → expected head assignments.
- **FM-to-Source Matcher** — Pairs FM line items with corresponding source-document values (the join logic behind all reconciliation).
- **Duplicate/Alternative Quote Detector** — Identifies multiple quotations for the same equipment/package; flags as duplicates/alternatives rather than summing.

## 4. Validation Engine

- **Reconciliation Core** — Compares an FM value against a source value with configurable tolerance → returns variance amount/percentage.
  - *Test:* pure function, numeric cases including edge cases (missing, zero, negative).
- **Flag Taxonomy Module** — Assigns one of the six statuses per check: confirmed match / variance / unsupported FM value / source value missing from FM / insufficient evidence / unable to validate.
- **25 Discrepancy Check Rules** — Implement each of the 25 checks (BRD §3.1) as an independent rule module consuming extractor outputs and the reconciliation core, grouped as:
  - Historical Financials & Operations (checks 1–6)
  - Project Cost & CapEx (checks 7–13)
  - Capital Structure, Debt & Solvency (checks 14–19)
  - Technical Specs & Operations / DPR (checks 20–22)
  - Tax, Timing & Capitalization (checks 23–25)

  Each rule: inputs (structured records) → list of findings with evidence references. Testable per-rule with crafted FM + document fixture pairs.
- **Lumpsum Header Detector** — Scans FM for aggregated headers lacking itemized schedules (FR-1.3.1).
- **Shortfall Calculator** — Reconciles extracted quotation totals vs. FM aggregated head; computes unquoted gap (FR-1.3.3).

## 5. Aggregation & Traceability Layer

- **Discrepancy Register Builder** — Consolidates all rule findings into the register: FM value, evidence value, discrepancy nature, period/line item, source reference, review status.
- **Project Summary Compiler** — Compiles the Project Summary tab from DPR + FM facts; surfaces conflicts instead of resolving them; never invents missing data.
- **Evidence Lineage Store** — Maps every extracted value/finding to source document, page, and line item (powers clickable lineage).

## 6. Review State Layer

- **Review Status Tracker** — Per-finding analyst review states (pending/accepted/rejected/notes).
- **Workspace State Manager** — Holds the current analyst-reviewed snapshot that exports must reflect.

## 7. Export Layer

- **Word Export Generator** — Discrepancy register + Project Summary → `.docx`.
- **PDF Export Generator** — Same content → PDF.
  - *Test both:* fixed register/summary fixture → assert output file structure/content.

## 8. UI Layer

- Upload interface (FM + supporting docs)
- Quotation list view per cost head with **copy list** action
- Discrepancy register view with status badges and filters
- Project Summary tab
- Side-by-side FM value ↔ source document evidence preview

---

## Cross-Cutting Constraints (non-functional)

- Encryption at rest/in transit for all uploaded client documents.
- Pipeline must process FM + 15 supporting docs in < 3 minutes → informs whether extraction is async/queued.
- Every component outputs structured, serializable records so the pipeline is resumable and auditable.

## Architectural Takeaway

- **Layers 1–3** are pure extraction/normalization (deterministic-ish, heavily unit-testable).
- **Layer 4** is pure business rules — the 25 checks as isolated rule modules — the heart of Phase 1.
- **Layers 5–8** are presentation and state.

If rule modules stay decoupled from ingestion and UI, each of the 25 checks can be developed, tested, and signed off independently — matching the BRD's stakeholder sign-off requirement on the discrepancy matrix.
