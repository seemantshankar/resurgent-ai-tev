# A spend ceiling continues the run locally

Cloud extraction and OCR already stop for a per-document cap and for a mandate locked to local processing. They do not stop because the mandate has spent its budget. Aborting at the ceiling would discard a reading the local path can still produce. Queueing the rest would need a worker this milestone does not have.

**Status.** Accepted.

**Decision.**

The spend ceiling is one amount in US dollars. Llama credits and other model spend are converted with the credit-to-dollar rate the usage ledger already applies. A mandate ceiling overrides a platform default. No ceiling means no new limit, and the existing per-document cap stays. A ceiling of zero means the run makes no cloud call.

The ceiling is checked before each cloud call, against that mandate's accumulated spend. A call that would cross the ceiling is skipped. The rest of the run continues locally, with the same result as a local-only mandate: no further cloud calls, unverified pages, ambiguous facts, and Unclear checklist items.

One policy command records the mandate's data residency and its spend ceiling, and, when the registry is named, the platform default.

**Considered options.**

- Abort the run when the ceiling is hit. Rejected: the pages already read stay useful, and the unread pages can still be marked Unclear. Stopping with nothing is a worse record than a partial local one.
- Queue the remaining cloud calls until the budget is raised. Rejected: a queue is a worker, and this milestone has none. The operator re-runs when they raise the ceiling.
- An analyst spending against a dashboard. Rejected: extraction has no human gate. The ceiling is a recorded limit, not a review step.

**Consequences.**

- One run can hold both cloud-backed facts and locally unverified ones. The unverified ones stay visible as ambiguous and Unclear, not as Provided.
- The per-document cap remains a second guard inside a ceiling that has not yet been reached.
- The offline drill is the proof that this degraded path finishes when no cloud call is allowed at all.
