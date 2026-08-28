# Send a number-redacted sheet to external models; keep real amounts on the cell graph

Region geometry and per-cell purpose will be proposed by an external model that must see labels and formula text, but must not see client amounts. We will **build a redacted sheet in the FM Loader** (dummy numeric literals only), send that view out, and write labels, region membership, roles, and purpose back onto the existing cell rows. Amounts never leave the database and are never reconstructed from the model.

This does not reopen ADR 0004. That ADR rejected a dummy-value twin as a *committed test fixture* because classification needs real labels, and committing those labels would disclose the private workbook. A redacted sheet as *LLM input* is the opposite trade: labels must stay so the model can name regions; numbers must go so financials do not leave the loader. A full dummy twin (labels and numbers) would make the model useless. Round-tripping amounts through the model would create a second, drift-prone copy of the graph we already own.

The model does not write SQLite directly. Analyst review gates model proposals via `review_queue`. See ADR 0009 for what the loader no longer does at ingest time.

**Superseded by ADR 0009:** the prior claim that cost-head totals, scratch/support heuristics, and column-role vocabulary stay in the parser.
