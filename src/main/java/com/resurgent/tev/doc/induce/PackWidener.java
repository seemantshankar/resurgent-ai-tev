package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.pack.ClauseSection;
import com.resurgent.tev.doc.pack.DocTypePack;
import com.resurgent.tev.doc.pack.PackClauseType;
import com.resurgent.tev.doc.pack.PackField;
import com.resurgent.tev.doc.pack.PackPassage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Continuous pack evolution. A later induction keeps every path already published
 * and adds newly discovered fields, tables, clauses, passages, and aliases.
 * New fields are optional until a later multi-document run says otherwise.
 */
final class PackWidener {

    private PackWidener() {}

    static WidenResult widen(DocTypePack previous, DocTypePack incoming) {
        List<Generalizer.DecisionNote> notes = new ArrayList<>();
        Map<String, PackField> fields = new LinkedHashMap<>();
        for (PackField field : previous.fields()) {
            fields.put(field.key(), field);
        }
        for (PackField field : incoming.fields()) {
            PackField prior = fields.get(field.key());
            if (prior == null) {
                PackField added = new PackField(
                        field.key(), field.path(), field.pathRoot(), field.valueKind(),
                        false, field.slots(), field.promptHint(), field.synonyms());
                fields.put(field.key(), added);
                notes.add(Generalizer.note(
                        field.key(),
                        field.required() ? "demoted_optional" : "promoted",
                        field.required() ? "widen_new_path" : "widen_optional",
                        "New path on a widened pack is optional."));
                continue;
            }
            fields.put(field.key(), new PackField(
                    prior.key(),
                    prior.path(),
                    prior.pathRoot(),
                    prior.valueKind(),
                    prior.required(),
                    prior.slots().isEmpty() ? field.slots() : prior.slots(),
                    prior.promptHint() == null || prior.promptHint().isBlank()
                            ? field.promptHint() : prior.promptHint(),
                    mergeStrings(prior.synonyms(), field.synonyms())));
        }

        Map<String, Map<String, Object>> tables = new LinkedHashMap<>();
        for (Map<String, Object> table : previous.tables()) {
            tables.put(String.valueOf(table.get("name")), table);
        }
        for (Map<String, Object> table : incoming.tables()) {
            String name = String.valueOf(table.get("name"));
            if (!tables.containsKey(name)) {
                tables.put(name, table);
                notes.add(Generalizer.note(name, "promoted", "widen_table",
                        "Schedule table added while widening the pack."));
            }
        }

        ClauseSection clauses = mergeClauses(previous.clauseSection(), incoming.clauseSection(), notes);
        List<PackPassage> passages = mergePassages(previous.passages(), incoming.passages(), notes);
        String dateKind = previous.documentDateKind() != null
                ? previous.documentDateKind()
                : incoming.documentDateKind();
        DocTypePack widened = new DocTypePack(
                previous.code(),
                previous.version(),
                previous.family(),
                previous.fileRole(),
                mergeStrings(previous.industryTags(), incoming.industryTags()),
                mergeStrings(previous.identitySignals(), incoming.identitySignals()),
                List.copyOf(fields.values()),
                List.copyOf(tables.values()),
                mergeStrings(previous.checklist(), incoming.checklist()),
                mergeStrings(previous.reportSections(), incoming.reportSections()),
                dateKind,
                clauses,
                passages);
        return new WidenResult(widened, List.copyOf(notes));
    }

    private static ClauseSection mergeClauses(
            ClauseSection previous, ClauseSection incoming, List<Generalizer.DecisionNote> notes) {
        if ((previous == null || !previous.declared()) && (incoming == null || !incoming.declared())) {
            return ClauseSection.NONE;
        }
        Map<String, PackClauseType> types = new LinkedHashMap<>();
        if (previous != null) {
            for (PackClauseType type : previous.types()) {
                types.put(type.key(), type);
            }
        }
        if (incoming != null) {
            for (PackClauseType type : incoming.types()) {
                PackClauseType prior = types.get(type.key());
                if (prior == null) {
                    types.put(type.key(), type);
                    notes.add(Generalizer.note(type.key(), "promoted", "widen_clause",
                            "Clause type added while widening the pack."));
                } else {
                    types.put(type.key(), new PackClauseType(
                            prior.key(), prior.label(), prior.parentFamily(),
                            mergeStrings(prior.aliases(), type.aliases())));
                }
            }
        }
        boolean open = (previous != null && previous.openExtraction())
                || (incoming != null && incoming.openExtraction());
        return new ClauseSection(true, open, List.copyOf(types.values()));
    }

    private static List<PackPassage> mergePassages(
            List<PackPassage> previous, List<PackPassage> incoming,
            List<Generalizer.DecisionNote> notes) {
        Map<String, PackPassage> byTopic = new LinkedHashMap<>();
        if (previous != null) {
            for (PackPassage passage : previous) {
                byTopic.put(passage.topic(), passage);
            }
        }
        if (incoming != null) {
            for (PackPassage passage : incoming) {
                PackPassage prior = byTopic.get(passage.topic());
                if (prior == null) {
                    byTopic.put(passage.topic(), passage);
                    notes.add(Generalizer.note(passage.topic(), "promoted",
                            "widen_passage", "Passage topic added while widening the pack."));
                } else {
                    byTopic.put(passage.topic(), new PackPassage(
                            prior.topic(),
                            mergeStrings(prior.headingTerms(), passage.headingTerms()),
                            mergeStrings(prior.fields(), passage.fields())));
                }
            }
        }
        return List.copyOf(byTopic.values());
    }

    private static List<String> mergeStrings(List<String> left, List<String> right) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        addUnique(out, seen, left);
        addUnique(out, seen, right);
        return List.copyOf(out);
    }

    private static void addUnique(List<String> out, LinkedHashSet<String> seen, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (seen.add(value.toLowerCase(Locale.ROOT))) {
                out.add(value);
            }
        }
    }

    record WidenResult(DocTypePack pack, List<Generalizer.DecisionNote> notes) {}
}
