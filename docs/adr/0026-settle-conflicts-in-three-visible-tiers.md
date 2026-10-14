# Settle conflicts in three visible tiers

BRD §3.7.5 says the platform shall surface a conflict between two credible sources and "shall not silently choose a value unless an explicit source-precedence rule has been configured". Epic #128 lists "silently resolving conflicts" as a non-goal, and architecture §12 says "surface, do not auto-pick". Real document sets contradict themselves all the time. A DPR's chapter 2 cites an old project cost and chapter 7 the revised one. A revised quotation restates a total. Sending every one of these to the analyst floods them and blocks downstream drafting. This decision keeps the BRD's "never silently" and lets the system settle what it can.

**Decision.**

1. A conflict is two credible sources stating different values for the same data point about the same entity, site and period. The sources can be two documents, two sections of one document, or a document and the financial model. Different as-on dates or periods are not conflicts. Values are compared in the same unit and scale with about 1% rounding tolerance, dates exactly, and names loosely.
2. **Tier 1, rule.** An explicit precedence rule settles it. An amendment beats the original. A document that says it supersedes another beats it. A later document date beats an earlier one, with the upload date only as a tie-breaker (M4). The losing value is kept and marked superseded.
3. **Tier 2, AI judgement.** With no rule, the system may pick a value only with written reasoning that cites both pages. The pick is stored as "settled by AI". It appears on the analyst's flagged list with the value used, the value rejected and the reason. The analyst does not have to act on it.
4. **Tier 3, flagged.** When the system cannot tell which value is right, the checklist status is Conflicting and it is flagged. No value is chosen.
5. Every conflict, and how it was settled, is a row in `workspace.db`. Nothing is overwritten, and no side file is the record.
6. The Excel financial model is read and never changed. A document-versus-model conflict is recorded; it does not edit a cell.

**Consequences.** Tier 2 refines BRD §3.7.5. It is not silent, because every AI pick is visible with its reasoning and both sources. It is not a configured precedence rule either, so this ADR is the authority for it. Report and FM outputs that use a tier-2 value still pass through the analyst-in-the-loop gates of BRD §3.3–3.5. Until M4 adds version precedence, M3 shows two documents with different document dates as "versions differ" rather than as a conflict.
