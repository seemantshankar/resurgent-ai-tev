package com.resurgent.tev.doc.assess;

import com.fasterxml.jackson.databind.JsonNode;
import com.resurgent.tev.doc.db.AssessmentRepository;
import com.resurgent.tev.doc.db.AssessmentRepository.PageText;
import com.resurgent.tev.doc.db.AssessmentRepository.VersionRow;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.doc.llm.DocLlmException;
import com.resurgent.tev.doc.llm.DocLlmJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code assess_checklist} Not-applicable step (M3-11, issue #144): the model reads
 * the mandate's documents against every item the deterministic engine found Not
 * provided and decides whether the documents show the item does not apply to this
 * project. It answers once per run, with a one-line reason and a verbatim quote per
 * item — the quote must anchor to a page, so a Not applicable verdict always records
 * the reason and the page; an unanchorable or missing verdict leaves the item Not
 * provided (still asked for). Nobody marks Not applicable by hand — the system
 * decides from the documents and records why, and the item raises no review_queue
 * flag (there is nothing for a human to recheck).
 */
public final class ChecklistNotApplicableScan {

    private static final String SYSTEM = """
            You check the expected checklist items of a project-finance mandate against \
            the mandate's documents: do the documents show that an item does not apply \
            to this project? An item does not apply when the documents state the \
            situation it covers is absent — the activity does not happen, the category \
            does not exist, or the site or entity the item is listed for is not part of \
            the project.
            Reply with ONE JSON object:
            {"not_applicable": [
              {"key": "<item key exactly as listed>",
               "site": "<site name, only when the item is listed for that one site>",
               "entity": "<entity name, only when the item is listed for that one entity>",
               "reason": "<one line: what in the documents shows it does not apply>",
               "quote": "<verbatim text from a page that shows it does not apply>"}
            ]}
            List only items the documents clearly show do not apply; omit every item \
            you are unsure about. Never guess.""";

    private static final int MAX_COMPLETION_TOKENS = 1_000;

    private static final int MAX_DOCUMENT_CHARS = 12_000;

    private ChecklistNotApplicableScan() {}

    /**
     * Replaces Not provided results the documents show do not apply with Not
     * applicable ones (reason and page included); everything else is kept.
     *
     * @param plan the deterministic plan
     * @param mandateId mandate scope
     * @param assessment repository bound to the caller's transaction
     * @param client document LLM seam; never null here
     * @param usageSink receives the model call's usage
     * @return the updated plan, same order
     * @throws Exception if a read fails
     */
    public static List<ChecklistAssessor.Result> apply(List<ChecklistAssessor.Result> plan,
            long mandateId, AssessmentRepository assessment, DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) throws Exception {
        List<ChecklistAssessor.Result> targets = new ArrayList<>();
        for (ChecklistAssessor.Result result : plan) {
            if (ChecklistAssessor.STATUS_NOT_PROVIDED.equals(result.status())) {
                targets.add(result);
            }
        }
        if (targets.isEmpty()) {
            return plan;
        }
        List<PageSource> pages = readPages(mandateId, assessment);
        if (pages.isEmpty()) {
            return plan;
        }
        List<Entry> entries = ask(client, targets, pages, usageSink);
        if (entries.isEmpty()) {
            return plan;
        }
        List<ChecklistAssessor.Result> updated = new ArrayList<>(plan.size());
        for (ChecklistAssessor.Result result : plan) {
            updated.add("not_provided".equals(result.status())
                    ? decide(result, entries, pages) : result);
        }
        return List.copyOf(updated);
    }

    /** The verdict for one result: matched, reasoned and anchored — else unchanged. */
    private static ChecklistAssessor.Result decide(ChecklistAssessor.Result result,
            List<Entry> entries, List<PageSource> pages) {
        for (Entry entry : entries) {
            if (!entry.matches(result)) {
                continue;
            }
            PageSource anchor = anchor(pages, entry.quote());
            if (anchor == null) {
                // The rule for a Not applicable verdict: reason and page. A quote the
                // documents cannot anchor flips nothing.
                System.err.println("[doc-assess] not-applicable quote for '"
                        + result.item().key() + "' does not anchor to any page — item stays"
                        + " not provided");
                return result;
            }
            return notApplicable(result, entry.reason(), anchor, entry.quote());
        }
        return result;
    }

    /** The Not applicable result: the model's reason and the page the quote anchors to. */
    private static ChecklistAssessor.Result notApplicable(ChecklistAssessor.Result result,
            String reason, PageSource anchor, String quote) {
        return new ChecklistAssessor.Result(
                result.item(), result.checklistCode(), result.checklistVersion(),
                result.definitionScope(), result.country(), result.industry(), result.note(),
                result.entityId(), result.entityName(), result.siteId(), result.siteName(),
                ChecklistAssessor.STATUS_NOT_APPLICABLE, reason,
                anchor.documentVersionId(), anchor.fileName(), anchor.pageIndex(),
                null, quote, null);
    }

    /** Every page with text of every active version, document order. */
    private static List<PageSource> readPages(long mandateId, AssessmentRepository assessment)
            throws Exception {
        List<PageSource> pages = new ArrayList<>();
        for (VersionRow version : assessment.versionsForMandate(mandateId)) {
            for (PageText page : assessment.pageTexts(version.documentVersionId())) {
                if (page.text() != null && !page.text().isBlank()) {
                    pages.add(new PageSource(version.documentVersionId(), version.fileName(),
                            page.pageIndex(), page.text()));
                }
            }
        }
        return pages;
    }

    /** Asks once about every Not provided item against the mandate's document text. */
    private static List<Entry> ask(DocLlmClient client,
            List<ChecklistAssessor.Result> targets, List<PageSource> pages,
            Consumer<DocLlmClient.Usage> usageSink) {
        String user = user(targets, pages);
        if (user.isBlank()) {
            return List.of();
        }
        try {
            DocLlmClient.Completion completion = client.complete(SYSTEM, user,
                    MAX_COMPLETION_TOKENS);
            usageSink.accept(completion.usage());
            return interpret(completion.content());
        } catch (DocLlmException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] not-applicable LLM failed: " + e.getMessage());
            return List.of();
        } catch (RuntimeException e) {
            usageSink.accept(DocLlmClient.Usage.failed(client.id(), 0));
            System.err.println("[doc-assess] not-applicable LLM failed: " + e.getMessage());
            return List.of();
        }
    }

    /** The items still not provided plus the document text the verdict must rest on. */
    static String user(List<ChecklistAssessor.Result> targets, List<PageSource> pages) {
        StringBuilder user = new StringBuilder("Items still not provided:\n");
        for (ChecklistAssessor.Result result : targets) {
            user.append("- ").append(result.item().label())
                    .append(" (key: ").append(result.item().key())
                    .append(", ").append(result.item().level())
                    .append('/').append(result.item().granularity());
            if (result.siteName() != null) {
                user.append(", site: ").append(result.siteName());
            }
            if (result.entityName() != null) {
                user.append(", entity: ").append(result.entityName());
            }
            user.append(", expected document: ").append(result.item().documentType())
                    .append(")\n");
        }
        user.append("\nDocuments:\n");
        int used = 0;
        for (PageSource page : pages) {
            if (used >= MAX_DOCUMENT_CHARS) {
                break;
            }
            String header = "[file: " + page.fileName() + ", page: " + page.pageIndex() + "]\n";
            user.append(header).append(page.text()).append('\n');
            used += header.length() + page.text().length();
        }
        return user.length() <= MAX_DOCUMENT_CHARS + targets.size() * 200
                ? user.toString() : user.substring(0, MAX_DOCUMENT_CHARS);
    }

    /** Interprets the model reply; tolerant of prose around the JSON object. */
    static List<Entry> interpret(String content) {
        JsonNode node;
        try {
            node = DocLlmJson.object(content);
        } catch (DocLlmException e) {
            return List.of();
        }
        JsonNode list = node.get("not_applicable");
        if (list == null || !list.isArray()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode entry : list) {
            String key = text(entry, "key");
            String reason = text(entry, "reason");
            String quote = text(entry, "quote");
            if (key == null || reason == null || quote == null) {
                System.err.println("[doc-assess] ignoring incomplete not-applicable verdict: "
                        + entry);
                continue;
            }
            entries.add(new Entry(key, text(entry, "site"), text(entry, "entity"),
                    reason, quote));
        }
        return List.copyOf(entries);
    }

    /** The line a verdict quote came from: first page whose line contains it. */
    private static PageSource anchor(List<PageSource> pages, String quote) {
        String needle = quote.strip();
        if (needle.isEmpty()) {
            return null;
        }
        for (PageSource page : pages) {
            for (String line : page.text().split("\\R")) {
                String normalized = AssessmentRepository.normalizeWhitespace(line);
                if (normalized != null && normalized.toLowerCase(Locale.ROOT)
                        .contains(needle.toLowerCase(Locale.ROOT))) {
                    return page;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    /** One page with text, with its version and file for the anchor. */
    private record PageSource(long documentVersionId, String fileName, int pageIndex,
            String text) {}

    /** One model verdict: which item, which scope, why, and the quote behind it. */
    private record Entry(String key, String site, String entity, String reason, String quote) {

        /** True when this verdict is about exactly the result's item and scope. */
        boolean matches(ChecklistAssessor.Result result) {
            if (!key.equals(result.item().key())) {
                return false;
            }
            if (result.siteName() != null) {
                return site != null && site.equalsIgnoreCase(result.siteName());
            }
            if (result.entityName() != null) {
                return entity != null && entity.equalsIgnoreCase(result.entityName());
            }
            return site == null && entity == null;
        }
    }
}
