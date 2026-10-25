# Read each document from its contents and answer a report from every store

Clients send one file that may contain several works, and a project report often never calls itself a DPR. A report then asks for a figure in ordinary words, not in the words or the field name under which it was stored. A file holds as many documents as the client included, each typed from the contents of its own pages, and a report question is answered from any stored form of that information.

**Status.** Accepted. Supersedes, in [ADR 0030](0030-autonomous-web-grounded-pack-induction.md), the step that re-classifies an induced pack by heading phrases, and the assumption that one file has one document type. Web-grounded induction of a genuinely new kind of document, single-document generalisation, and pack widening remain.

**Decision.**

A file is what the client sent. A document is one work inside it, on the pages that state that work, with no limit on how many a file may hold. A work interrupted by another document is still one document. A second work of the same type is its own document, and an amendment supersedes only the facts it restates. A page that states two works belongs to both, and each statement belongs to the work it is part of. Pages that state no work, such as an index or a blank page, belong to no document. A heading, a title, or a filename that only names a type does not start a document and does not assign one.

The type is read from the contents those pages state, including when the wording differs from the usual labels. A fact that more than one type states does not assign the type. One borrowed figure does not create a document. A kind of document no known type fits is induced from those contents, and that type is kept. Pages from which nothing can be induced stay unknown. A cover that only names a type stays unknown, and that checklist line stays Not provided. A document whose contents are present makes its checklist line Provided.

Each document is read on its own pages. Labeled lines are taken first. Lines and table rows the labels missed are then read, including a figure that continues a sentence or a table from an earlier page. The figure keeps that meaning for as long as the sentence or the table goes on. A cloud model is used only when the mandate's data residency allows it, and only for what that local reading still could not read. The date of a document is the date stated on its own pages.

A report question is asked in ordinary words. It is answered from a fact, a document's type, a passage, a clause, or the words on the page, and it does not have to use the stored wording or the field's name. A current fact is the record of that amount. A disagreement between stores stays visible. A number whose surrounding pages never say what it is comes back as that number, not as an invented meaning.

**Considered options.**

- One type for the whole file, leaving a file that contains two works as neither, with neither checklist line Provided and no facts taken. Rejected: the client provided both documents, and the facts have to be classified.
- Assigning the type from a heading or a filename such as "DPR" or "PPA". Rejected: many project reports never use those words, and a short mention of "PPA" inside another document was being treated as the type.
- Treating each page on its own, so a figure carried over a page break has no meaning. Rejected: the split is formatting. The sentence or the column heading is on an earlier page.

**Consequences.**

- Clause reading and extraction follow a document's own pages, including a page shared with another document.
- A report question has to look past the fact store. A question that does not repeat the stored words still has to find them, including when the mandate does not allow a cloud service.
- Two quotations in one file are two documents. The quotation checklist line is Provided, and both sets of facts remain. An amendment bound in the same file is a second document and is settled under [ADR 0028](0028-resolve-document-version-precedence-and-per-fact-supersession.md).
