# Reject schema paths as verbatim quotes

**Status.** Accepted (grill Round 1, 2026-10-27).

When the cloud extractor returns citations, it sometimes puts a JSON schema path (for example `profit_and_loss_account.0.sections.2.items.12.label`) in `matching_text` instead of words from the PDF. Those strings are not **layout text** and must not be stored as **verbatim quote (fact evidence)** or used for quote resolution against spans, blocks, or parsed pages.

The adapter treats path-shaped `matching_text` as absent and falls back to the extracted value text for the quote when that text plausibly appears on the cited page. Quote resolution remains a text match against stored layout text; it is not proof the extracted meaning is correct. Runs may stay **partial** when citations remain missing after that fallback.

This applies to every document and every extract schema — not a sample-document exception. Meaning on the shared nomenclature still comes from pack bind and document nomenclature, not from schema paths.
