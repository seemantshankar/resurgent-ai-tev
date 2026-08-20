# TEV Automation for Resurgent India

# Business Requirements Document (BRD)

---

**Project Title:** TEV Report Generation & Workflow Automation

**Document Version:** 1.2

**Date:** August 5, 2026

**Author:** Seemant Shankar

**Target Audience:** Business Operations, TEV Lead Analysts, Product & Engineering Teams

## 1. Executive Summary & Objectives

Resurgent India’s Techno-Economic Viability (TEV) division relies on a heavily manual workflow to progress from mandate acquisition to final report delivery. Internal analyst interviews indicate that **Step 4 (Financial Model Preparation)** and **Step 7 (TEV Draft Report Creation)** together account for approximately **75–80% of total analyst effort and time**.

The objective of this initiative is to build an automated, AI-assisted platform to reduce analyst cognitive load, ensure high data accuracy against supporting documents, and significantly increase per-analyst report throughput.

### Current TEV Workflow Analysis

The existing 8-step process for generating TEV reports involves the following stages and effort distribution:

![TEV Automation Workflows - TEV Mandate - Report Process Workflow.jpg](TEV%20Automation%20for%20Resurgent%20India/TEV_Automation_Workflows_-_TEV_Mandate_-_Report_Process_Workflow.jpg)

### Process Step Details & Dependencies

| **Step #** | **Step Name** | **Key Process Activities** | **Analyst Effort** | **Key Dependencies** |
| --- | --- | --- | --- | --- |
| **1** | Document Gathering | Checklist sent to client; documents tracked manually upon receipt via email/WhatsApp. | **Minimal** (Dependent on client turnaround) | Client responsiveness |
| **2** | Client Interviews & Data Validation | Validate client-submitted data and financial model assumptions with the client. | **Moderate** | Client cooperation |
| **3** | Market Research | Conduct industry research (Google/AI models, industry reports), validate market data, compare with past TEV reports. | **Moderate** (1–2 days) | Analyst research capability |
| **4** | Financial Model (FM) Preparation | Re-align messy client FM to Resurgent templates; expand missing line items; insert projection assumptions. | **High (5–6 days)** | Market research & manual data mapping |
| **5** | FM Audit with Client & Banker | Present FM to client/banker; incorporate iterative feedback. | **Moderate** (Manual tinkering) | Stakeholder availability |
| **6** | Field Visit | On-site team captures site photos, infrastructure data, and completes site visit forms. | **Minimal** (~2 days manual field effort) | Field personnel throughput |
| **7** | TEV Draft Report Creation and Presentation | Draft comprehensive report with commentary on projections, assumptions, industry research, promoter backgroud checks, and peer comparisons. | **High** (4–5 days) | Finalized FM & market research |
| 8 | Draft Report Presentation | Draft Report vetted by Client and Banker. Suggestions, comments and observations incorporated into draft report. | **Moderate** (~6-7 Days) | Heavily dependent on client/banker availability |
| 9 | Final Report Presentation | Internal approval routing and delivery of the finalized TEV report. | **Minimal** (1 day) | Signoffs |

## 2. Phased Delivery Roadmap & Effort Breakdown

To maximize early value while preserving analyst control over the Financial Model and report narrative, the platform roadmap is structured into **6 distinct phases**. The revised sequencing separates (i) validation of client-submitted inputs, (ii) audit of the analyst-completed FM, (iii) generation of core TEV report sections, (iv) generation of dependent analytical sections, (v) assembly and controlled refresh of the full TEV report, and (vi) automation of FM generation.

Effort reductions should be treated as indicative and will be validated during implementation as capabilities mature and overlap across phases.

| Phase | Phase Title | What it **Will Do** | Primary Analyst Control / Boundary |
| --- | --- | --- | --- |
| 1 | Client FM Validation Against Supporting Documents | ✅ Validates the client-submitted FM against quotations and all other supporting details/documents submitted
✅ Flags discrepancies, mismatches, unsupported values and missing evidence
✅ Extracts quotation items under the relevant project-cost heads and provides an option to copy the generated list
✅ Provides a concise project summary tab compiled from the client's DPR and FM
✅ Allows the discrepancy register and project summary to be downloaded in Word and/or PDF format | The system presents evidence-backed findings and extracted data for analyst review; it does not silently overwrite the client's FM. |
| 2 | Audit of Analyst-Completed Financial Model | ✅ Audits the FM after the analyst has completed/reworked it
✅ Verifies formula linkages, assumptions, computational integrity and internal consistency
✅ Recalculates and validates relevant financial ratios and sensitivities where applicable
✅ Flags broken links, hard-coded anomalies, inconsistent assumptions, circularities, computational errors and other model discrepancies | The analyst remains responsible for approving and making substantive changes to the FM. |
| 3 | Generation of Core TEV Report Sections | ✅ Independently generates the Financial Viability, Marketing & Industry Analysis, About the Company & Promoters, and Technical Viability sections
✅ Captures MCA and other relevant online/public data
✅ First presents evidence-backed bullet points compiled from uploaded documents, structured data and external sources
✅ Allows the analyst to edit, add, remove or correct bullet points before confirmation
✅ Generates the complete prose section only after analyst confirmation | Each section is independently reviewable and cannot be treated as final until confirmed by the analyst. |
| 4 | Generation of Dependent Analytical Sections | ✅ Generates sections such as Risk Analysis & Mitigation, SWOT Analysis and other dependent analytical sections
✅ Draws on the finalised Phase 3 sections together with any additional analyst inputs or supporting information | Dependent sections are generated only after the prerequisite core sections contain sufficiently reviewed information. |
| 5 | Full TEV Report Generation & Controlled Refresh | ✅ Assembles the complete TEV report once all constituent sections have been finalised and confirmed by the analyst
✅ Preserves section-level traceability to source information and analyst-approved content
✅ Allows revised/new data or documents to be uploaded after draft generation
✅ Identifies affected sections and regenerates/updates the relevant section(s) rather than requiring the entire report to be recreated manually | No complete report is treated as final until all required sections have analyst confirmation. Updates must preserve analyst review before revised sections become final. |
| 6 | Automated Financial Model Generation | ✅ Automates FM generation as far as practicable using validated project inputs, supporting documents, approved assumptions and established modelling templates/rules
✅ Builds on the validation, audit and traceability capabilities established in earlier phases | This is the most advanced phase and remains subject to feasibility, template standardisation and performance demonstrated in Phases 1–5. |

## 3. Detailed Functional Requirements — Phases 1–6

> **Core System Principle:** The platform operates as an evidence-backed copilot to the TEV analyst. In Phases 1–5 it must not silently overwrite the client's or analyst's Financial Model, approved bullet points, or finalised report sections. It ingests and structures source information, validates and audits the FM, surfaces discrepancies with traceable evidence, assists the analyst in reviewing and confirming report content, and regenerates affected outputs when underlying information changes. Phase 6 separately introduces automated FM generation as far as practicable. Human review and confirmation remain explicit control points throughout the workflow.
> 

![TEV Automation Workflows - Phase 1&2 Workflow Chart.jpg](TEV%20Automation%20for%20Resurgent%20India/TEV_Automation_Workflows_-_Phase_12_Workflow_Chart.jpg)

### 3.1 Phase 1 — Client FM Validation Against Quotations and Other Submitted Details

#### Additional Client-Confirmed Phase 1 Requirements

- In addition to all detailed discrepancy and reconciliation checks specified below, the system shall ingest the client-submitted FM, DPR, quotations and other supporting documents and preserve evidence traceability for material validations.
- The system shall extract individual quotation items and classify them under the applicable project-cost heads/sub-heads. The UI shall provide a consolidated quotation-derived list under each head with an option for the analyst to **copy the generated list**.
- Where multiple quotations relate to the same equipment/package/component, quotation-level breakup shall be preserved and apparent duplicates or alternatives shall be identified rather than automatically summed.
- Quotation values shall be reconciled against corresponding FM project-cost values. Material attributes available in the quotation—such as quantity, unit rate, taxes/duties, freight, installation, currency and validity—shall be retained where present.
- A dedicated **Project Summary** tab shall compile a concise project overview from the client's DPR and FM, including available project description, location, proposed capacity, major project-cost heads, means of finance, implementation information and other material particulars.
- Missing information shall not be invented. Conflicting material facts between the DPR and FM shall be surfaced for analyst review rather than silently resolved.
- Phase 1 flags shall distinguish, where applicable, between confirmed match, variance/discrepancy, unsupported FM value, supporting-document value missing from the FM, insufficient evidence and unable to validate.
- All Phase 1 discrepancies shall be consolidated into an analyst-reviewable discrepancy register showing the FM value, supporting evidence/value, nature of discrepancy, relevant period/line item, source reference and review status.
- The **flagged discrepancies and Project Summary shall be downloadable in Word and/or PDF format** and shall reflect the current analyst-reviewed state of the workspace.

#### Phase 1 Discrepancy Matrix for Stakeholder Sign-off

<aside>
✅

The following **25 discrepancy checks constitute the proposed Phase 1 scope for stakeholder sign-off**. Each check shall be surfaced when applicable to the project and when the required source evidence is available. Findings shall follow the evidence-traceability and analyst-review controls defined above.

</aside>

#### Historical Financials & Operations

**Comparison documents:** Client FM vs. 3–5 Year Audited Balance Sheets, P&L Statements, & Notes to Accounts

| ID | Discrepancy Name | Primary Source Document | FM Target Area | Project Applicability |
| --- | --- | --- | --- | --- |
| 1 | Turnover / Revenue Variance | Audited P&L Statements | Historical Revenue Schedule | Brownfield |
| 2 | Cost & Margin Shift | Audited P&L Schedules | COGS, Power, Wages, Overheads | Brownfield |
| 3 | Net Worth Mismatch | Audited Balance Sheets | Share Capital & Reserves | Both (Existing Entity vs. New SPV with existing promoter history) |
| 4 | Existing Debt Variance | Audited BS / Debt Schedules | Liabilities Schedule | Brownfield |
| 5 | Revenue Classification Error | Notes to Accounts (P&L) | Core Turnover Schedule | Brownfield |
| 6 | Direct vs. Indirect Cost Error | Audited P&L Notes | COGS vs. Admin Expenses | Brownfield |

#### Project Cost & Capital Expenditure (CapEx)

**Comparison documents:** Client FM vs. Vendor Quotations, Civil Estimates, Proforma Invoices, & Architect Drawings

| ID | Discrepancy Name | Primary Source Document | FM Target Area | Project Applicability |
| --- | --- | --- | --- | --- |
| 7 | Aggregated Lumpsum Gap | Quotation Ingestion Package | CapEx Schedule (P&M, Civil) | Both (Critical for Greenfield) |
| 8 | Quotation Shortfall | Extracted Vendor Invoices | Total Project Cost Head | Both (Critical for Greenfield) |
| 9 | CapEx vs. OpEx Misclassification | Vendor Quotes / DPR Descriptions | CapEx vs. OpEx Schedules | Both |
| 10 | Tax (GST/Duties) Misalignment | Quotation Terms & Fine Print | Gross CapEx / Tax Schedules | Both |
| 11 | Freight & Duty Omission | Vendor Proforma Invoices | Installed Equipment Cost | Both |
| 12 | Civil Estimate Mismatch | Architect Drawings / Estimates | Civil & Structural CapEx | Both (Dominant in Greenfield) |
| 13 | Expired Quote Flag | Vendor Quotations | Cost Assumptions | Both |

#### Capital Structure, Debt & Solvency Means

**Comparison documents:** Client FM vs. Director Net Worth Certificates, Sanction Letters, & Promoter Bank Statements

| ID | Discrepancy Name | Primary Source Document | FM Target Area | Project Applicability |
| --- | --- | --- | --- | --- |
| 14 | Promoter Equity Deficit | Director Net Worth Certificates | Means of Finance (Equity) | Both (Critical for Greenfield) |
| 15 | Unbacked Unsecured Loans | Net Worth Certs / Bank Statements | Quasi-Equity / Debt Schedule | Both |
| 16 | Quasi-Equity Misclassification | Bank Sanction Letters / Agreements | Pure Equity vs. Term Debt | Both |
| 17 | Debt Tenor & Rate Mismatch | Existing Bank Sanction Letters | Debt Amortization Schedule | Both |
| 18 | Current Debt Classification Error | Debt Schedules / Sanction Letters | Current vs. Non-Current Liabilities | Brownfield |
| 19 | Working Capital Limit Mismatch | Working Capital Sanction Letters | Current Liabilities / Cash | Brownfield |

#### Technical Specifications & Operations (DPR)

**Comparison documents:** Client FM vs. Detailed Project Report (DPR) & Industry Norms

| ID | Discrepancy Name | Primary Source Document | FM Target Area | Project Applicability |
| --- | --- | --- | --- | --- |
| 20 | Capacity & Yield Mismatch | Technical DPR Sections | Production / Capacity Schedule | Both (Essential for Greenfield setup) |
| 21 | COD & Timeline Misalignment | Project Execution Schedule (DPR) | Revenue Start Date | Greenfield (Also applies to major Brownfield expansions) |
| 22 | Utility Consumption Gap | DPR Technical Specifications | Direct Power/Fuel/Water Costs | Both |

#### Tax, Timing & Capitalization Dynamics

**Comparison documents:** Client FM vs. Invoices, Construction Timelines, & Statutory Provisions

| ID | Discrepancy Name | Primary Source Document | FM Target Area | Project Applicability |
| --- | --- | --- | --- | --- |
| 23 | IDC Capitalization Error | Debt Drawdown & COD Schedule | Pre-Op Expense / OpEx Schedule | Greenfield (Also applies to major Brownfield expansions) |
| 24 | Unclaimed Input Tax Credit (ITC) | Vendor Quotations / Tax Schedules | Working Capital / Tax Offsets | Both |
| 25 | Premature Repayment Start | Bank Sanction Terms | Debt Amortization Schedule | Greenfield (Where moratorium period applies) |

#### Existing Detailed Reconciliation Logic

All detailed discrepancy checks below remain authoritative Phase 1 requirements and are cumulative with the client-confirmed requirements above.

#### FR-1.1: Document & File Ingestion

- **FR-1.1.1:** Accept and process unstructured client Financial Models in standard spreadsheet formats (`.xlsx`, `.xls`, `.csv`).
- **FR-1.1.2:** Ingest primary supporting documents:
    - Audited Balance Sheets & Profit & Loss Statements (3–5 years for Brownfield projects)
    - Equipment, Machinery, & Civil Work Quotations (Greenfield & Brownfield expansions)
    - Director/Promoter Net Worth Certificates
    - Detailed Project Reports (DPR) & Sanction Letters

#### FR-1.2: Cross-Document Data Validation

- **FR-1.2.1 (Historical Reconciliation):** Cross-reference historical figures in the client-provided FM against audited financials (e.g., verifying historical turnover and equity capital).
- **FR-1.2.2 (Discrepancy Highlighting):** Highlight numerical variances between the FM line items and primary source documents.
- **FR-1.2.3 (CapEx & Quotation Cross-Checking):** Cross-reference capital expenditure lines and project cost heads in the client-provided FM against vendor and contractor quotations (for both Greenfield and Brownfield projects) to validate cost assumptions, identify missing itemized breakups, and check for shortfalls.
- **FR-1.2.4 (Promoter Equity & Net Worth Verification):** Cross-reference promoter and director equity contribution figures stated in the client FM against official Net Worth Certificates to ensure equity funding is properly backed and validated.

#### FR-1.3: Line Item Breakdown & Smart Mapping

- **FR-1.3.1 (Aggregated Header Detection):** Scan the client FM for aggregated or lump-sum headers (e.g., generic "Plant & Machinery CapEx: ₹50 Cr" or "Civil Works") that lack supporting line-item schedules.
- **FR-1.3.2 (Vendor Quote & Invoice Extraction):** Parse ingested vendor quotations, civil estimates, and proforma invoices to extract itemized cost tables, unit costs, quantities, and taxes.
- **FR-1.3.3 (Unquoted Contingency & Shortfall Identification):** Reconcile total extracted quotation values against the aggregated FM line item to automatically flag shortfalls, missing vendor quotes, or unquoted contingency estimates (e.g., "Extracted quotes total ₹42 Cr out of ₹50 Cr; ₹8 Cr remains unquoted/unverified").
- **FR-1.3.4 (Tax & Freight Adjustment Mapping):** Identify whether vendor quotations include or exclude GST, duties, freight, and insurance, and verify if the FM accurately accounts for these grossed-up values.
- **FR-1.3.5 (Analyst Actionable Itemization Suggestion):** Present a side-by-side mapping interface in the UI where the analyst can preview, edit, and approve the platform's suggested itemised breakdown before importing it into their model workflow.

### 3.2 Phase 2 Functional Requirements: Financial Model Health, Ratio & Sensitivity Audit

#### FR-2.1: Key Metric & Bankability Ratio Audit

- **FR-2.1.1 (DSCR & Average DSCR Audit):** Automatically extract projected operational cash flows and principal/interest debt obligations to compute annual Debt Service Coverage Ratio (DSCR) and Average DSCR across the loan tenor. Flag any operational year where DSCR falls below bankability thresholds (e.g., `< 1.20x` annual, `< 1.35x` average).
- **FR-2.1.2 (Coverage & Solvency Ratios):** Calculate and highlight discrepancies in:
    - Interest Coverage Ratio (ICR)
    - Fixed Asset Coverage Ratio (FACR)
    - Debt Service Security Ratio (DSSR)
    - Security Coverage Ratio (SCR)
- **FR-2.1.3 (Capital Structure & Leverage Ratios):** Audit the proposed capital structure by computing and verifying:
    - Debt-to-Equity Ratio (DER) against norm limits (e.g., maximum `2:1` or `3:1` depending on sector)
    - Total Debt / EBITDA multiples
    - TOL / TNW (Total Outside Liabilities / Total Net Worth)
- **FR-2.1.4 (Working Capital & Liquidity Ratios):** Audit operational assumptions by extracting and computing:
    - Current Ratio & Quick Ratio
    - Inventory Holding Days, Receivables/Debtor Days, and Payables Days
    - Working Capital Gap (NWC) calculations against bank-sanctioned limits
- **FR-2.1.5 (Returns & Investment Viability):** Calculate and flag unrealistic projections for Project IRR, Equity IRR, Net Present Value (NPV, in case restructuring), and Payback Period / Break-Even Point (BEP % of capacity utilisation).
- **FR-2.1.6 (Promoter Contribution Verification):** Verify whether the promoter equity contribution meets minimum required norms (e.g., 25–30% of total project cost) against Net Worth Certificates and verify upfront equity deployment schedules relative to debt drawdowns.

#### FR-2.2: Automated Stress-Testing & Sensitivity Pre-Check

- **FR-2.2.1: Downside Scenario Simulation Engine**

The system shall run an automated, non-destructive stress-testing engine on the parsed client model data (without modifying the source file) and render interactive outcome cards in the UI for the following scenarios:

- **Scenario A (Revenue Drop & Cost Escalation):**
    - *Simulations:* Revenue drops (-5%, -10%) combined with Operating Cost increases (+5%, +10%).
    - *Impact Metrics:* Year-on-year DSCR, EBITDA margins, and Break-Even Point (BEP % capacity utilization).
- **Scenario B (Interest Rate Escalation):**
    - *Simulations:* Benchmark interest rate increases by +100 bps (+1%) and +200 bps (+2%).
    - *Impact Metrics:* DSCR, Average DSCR, Project IRR, Equity IRR, NPV, and Debt Servicing Burden.
- **Scenario C (CapEx Overrun):**
    - *Simulations:* Total project capital expenditure increases by +5% and +10%.
    - *Impact Metrics:* Revised Means of Finance, Debt-to-Equity Ratio, additional equity required from promoters, and Fixed Asset Coverage Ratio (FACR).
- **Scenario D (COD Delay / Execution Slippage):**
    - *Simulations:* Commercial Operation Date (COD) is delayed by 3 months and 6 months.
    - *Impact Metrics:* Interest During Construction (IDC) escalation, capitalized pre-operative expense increase, loan repayment moratorium shift, and total revised project cost.
- **Scenario E (Combined Worst-Case / Multi-Factor Stress Test):**
    - *Simulations:* Simultaneous execution of -5% Revenue, +5% OpEx, +100 bps Interest Rate, and 3-month COD Delay.
    - *Impact Metrics:* Crossover point where DSCR breaches covenants (`< 1.0x` or default state) and net cash flow deficits.

To accompany the scenario engine, we should include the display and alert logic:

- **FR-2.2.2 (Sensitivity Threshold & Covenant Breach Alerts):**
    - **Visual Indicators:** The UI shall display color-coded status badges for each scenario output:
        - **Green (Compliant):** DSCR ≥ 1.25x (or sector threshold)
        - **Yellow (Tight / Watchlist):** DSCR between 1.00x and 1.24x
        - **Red (Non-Bankable / Default):** DSCR < 1.00x or net cash deficit
    - **Breach Summary & Covenant Flags:** The system shall explicitly flag any downside scenario that causes debt default or breaches bank covenants, generating a summary that highlights the exact operating period (month/year) and financial metric where the failure occurs.

### 3.8 Cross-Phase Non-Functional & System Requirements

- **User Experience (UX):** Discrepancies and recommended breakdowns must be rendered directly in a clean UI workspace with source-document side-by-side previewing.
- **Data Lineage & Traceability:** Every flagged variance must provide clickable lineage pointing directly to the exact source document page and line item.
- **Performance:** Processing and auditing of a standard client file package (FM + up to 15 supporting PDFs/docs) should complete within **< 3 minutes**.
- **Security & Confidentiality:** Client financial documents contain sensitive trade data and must be encrypted at rest and in transit in compliance with organizational data protection policies.

#### Client-Confirmed Phase 2 Audit Scope and Controls

- Phase 2 applies to the **analyst-completed/reworked FM**, not the original client-submitted FM. The system shall preserve the distinction between these model versions.
- The system shall verify formula linkages, assumptions, computational integrity and internal consistency across the analyst-completed workbook.
- Formula/linkage checks shall include broken references, inconsistent formula patterns, accidental hard-coding within formula ranges, omitted cells, incorrect range references, cross-sheet/cross-period inconsistencies and circular references where detectable.
- Material modelling assumptions shall be tested for consistent application and compared against validated Phase 1 information and approved analyst inputs where applicable. Conflicting assumptions, unexplained period changes and assumptions inconsistent with linked schedules shall be flagged.
- Computational checks shall include incorrect totals/subtotals, roll-forward inconsistencies, schedule-to-summary mismatches, balance-sheet balancing, cash-flow movement, depreciation, interest, debt schedules, working-capital calculations, tax calculations and other relevant model mechanics where present.
- Existing DSCR, financial-ratio, covenant and sensitivity requirements in this Phase 2 section remain in scope but shall operate on the **analyst-completed FM**. System-calculated ratios shall be compared with model outputs and discrepancies flagged.
- Sector-specific or lender-specific thresholds shall only be applied where the relevant benchmark/rule has been provided or configured; the system shall not invent an approval criterion.
- Phase 2 shall produce a consolidated **FM Audit Register** covering formula/linkage issues, assumption issues, computational errors, ratio/sensitivity discrepancies and other model-integrity flags.
- Findings shall be presented for analyst review and shall not silently modify the audited FM.

### 3.3 Phase 3 — Generation of Core TEV Report Sections

#### 3.3.1 Independently Generatable Sections

The system shall support independent generation of at least the following core TEV report sections:

- **Financial Viability Section**
- **Marketing & Industry Analysis Section**
- **About the Company & Promoters Section**
- **Technical Viability Section**

Each section shall be independently initiated, reviewed, edited, confirmed and regenerated without requiring all other sections to be completed first, except where a specific dependency is necessary.

#### 3.3.2 Source Compilation

- The system shall compile information for each section from the applicable uploaded documents, validated FM data, DPR, analyst inputs and other approved information sources.
- The system shall capture relevant **MCA and other online/publicly available data** for sections where such information is required.
- Externally sourced facts shall retain source traceability so that the analyst can understand where the information originated.
- Conflicting information across sources shall be surfaced for review rather than silently resolved by the generation model.

#### 3.3.3 Mandatory Bullet-Point Review Stage

- Before generating narrative prose, the UI shall display **bullet points compiled from the relevant data sources, information and submitted documents** for that section.
- The bullet-point stage is a mandatory analyst-control gate between source compilation and prose generation.
- The analyst shall be able to edit, add, delete, correct or reorder the bullet points.
- Where practicable, generated bullet points shall retain links/references to their underlying evidence.
- The system shall distinguish system-generated information from analyst-added or analyst-modified information.

#### 3.3.4 Analyst Confirmation and Section Generation

- The complete narrative section shall be generated only after the analyst confirms the bullet points for that section.
- The generated narrative shall use the confirmed bullet points as the authoritative factual basis and follow the required Resurgent India TEV format/template.
- The generation process shall not introduce unsupported factual claims merely to make the prose appear complete.
- The analyst shall be able to review the generated section and make further edits before marking the section as finalised.
- Confirmation status shall be maintained independently for each section.

### 3.4 Phase 4 — Generation of Other / Dependent TEV Sections

#### 3.4.1 Dependent Analysis

- Once the relevant Phase 3 sections have been generated and reviewed, the system shall use their confirmed information as inputs for additional analytical sections.
- These shall include **Risk Analysis & Mitigation**, **SWOT Analysis**, and other similar TEV sub-sections required by the approved report format.
- The system may additionally use analyst-provided inputs or supporting information specifically supplied for these analyses.

#### 3.4.2 Consistency Requirements

- Risks, mitigants, strengths, weaknesses, opportunities and threats shall be grounded in the confirmed project information rather than generated as generic boilerplate.
- Phase 4 outputs shall remain consistent with the facts and conclusions contained in the finalised Phase 3 sections.
- If a Phase 3 input subsequently changes, the system shall identify dependent Phase 4 sections as potentially affected.
- The analyst shall retain the ability to review, edit and confirm each Phase 4 section before it is treated as final.

### 3.5 Phase 5 — Full TEV Report Generation and Controlled Updates

#### 3.5.1 Full Report Assembly

- The complete TEV report shall be generated/assembled only after all mandatory constituent sections have reached the required analyst-confirmed state.
- The system shall assemble sections according to the approved Resurgent India TEV report structure and formatting requirements.
- Analyst-approved edits to individual sections shall be preserved during full-report assembly.

#### 3.5.2 Revision and Impact Detection

- After a report or report section has been generated, the analyst shall be able to upload revised or additional documents, data or information.
- The system shall determine which facts/inputs have changed and identify the report sections that depend on those changed inputs.
- Unaffected sections shall not be unnecessarily regenerated.
- Affected sections shall be marked as requiring update/review and the system shall prepare revised content based on the new information.

#### 3.5.3 Reconfirmation and Version Control

- Automatically updated content shall **not bypass analyst approval**. A revised section shall return to an analyst-review state before becoming final.
- The system shall preserve sufficient version history to distinguish previously confirmed content from revised content.
- Where useful, the analyst shall be shown what materially changed and why a section was marked for regeneration.
- Reconfirming all affected sections shall allow the full report to return to a finalised state.

### 3.6 Phase 6 — Automated Financial Model Generation

#### 3.6.1 Objective and Boundary

- Phase 6 shall automate FM generation **as much as practicable**, using the capabilities, validated data structures and controls established in Phases 1–5.
- The system shall use approved modelling templates, validated source data, project assumptions and configured modelling rules to generate or populate the FM.
- The precise degree of automation may vary by project type and by the standardisation achievable across Resurgent India's FM templates.

#### 3.6.2 Control and Validation

- Generated FMs shall remain subject to analyst review and the Phase 2 audit controls before being relied upon for final TEV conclusions.
- The system shall preserve traceability between material generated FM assumptions/inputs and the source or analyst-approved basis used to populate them.
- Where the system cannot reliably infer a required assumption or modelling treatment, it shall request analyst input rather than silently inventing a value.

### 3.7 Cross-Phase Requirements

#### 3.7.1 Traceability

Material values, discrepancies, generated bullet points and generated report content shall retain traceability to their underlying documents, structured data, approved analyst inputs or external sources wherever practicable.

#### 3.7.2 Analyst-in-the-Loop Controls

The platform shall maintain explicit analyst review/confirmation gates at material decision points. Automation is intended to accelerate analysis and drafting without obscuring the factual basis of outputs or silently changing analyst-approved work.

#### 3.7.3 Dependency Awareness

The system shall maintain dependencies between source data, FM validations, approved report facts and downstream report sections so that changes to underlying information can be propagated to the correct affected outputs.

#### 3.7.4 Change Impact and Regeneration

When new or revised information is introduced, the system shall identify affected outputs, preserve unaffected approved work, regenerate only what is necessary, and require analyst reconfirmation for materially changed sections.

#### 3.7.5 Source Conflict Handling

Where two credible sources provide conflicting material information, the platform shall surface the conflict to the analyst. It shall not silently choose a value unless an explicit source-precedence rule has been configured.

## 5. Success Metrics & Key Performance Indicators (KPIs)

| **Metric** | **Target Baseline** | **Phases 1 & 2 Combined Target** | **Evaluation Method** |
| --- | --- | --- | --- |
| **Initial Audit & Ratio Check Time** | 1.0 – 1.5 Days (Manual validation & sensitivity setup) | **< 2 Hours** (Automated cross-check, ratio audit & stress tests) | Analyst effort log tracking |
| **Workflow Effort Savings** | 0% (Baseline) | **~20% Cumulative Reduction** in overall analyst turnaround time | Project completion turnaround time |
| **Discrepancy & Risk Detection Rate** | Variable (Human oversight risk) | **> 98%** of document-to-FM mismatches and bankability covenant breaches flagged | Quality review audits |

## 4. Stakeholder Signoff Request

By signing below, stakeholders agree to the scope, updated phase roadmap, and functional requirements outlined in this document.

| **Stakeholder Name** | **Role / Title** | **Signature** | **Date** |
| --- | --- | --- | --- |
| **Sanjeet Kumar** | Head - Business Development (TEV Division) | `_____________________` | `___ / ___ / 2026` |
| **Mayank Sethi** | Senior Manager - TEV Research & Appraisal | `_____________________` | `___ / ___ / 2026` |
| **Seemant Shankar** | AVP, AI Product Management | `_____________________` | `___ / ___ / 2026` |