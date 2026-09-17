package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Asks the model once per distinct qualified label, not once per cell.
 *
 * <p>The answer cache is request-scoped and keyed on the label alone, so two cells
 * carrying the same label can no longer be given different paths — divergence today
 * runs exactly along candidate, chunk, column and coordinate, which the key
 * excludes. The graph is what gets persisted; this cache is not, and a run is
 * reproducible because the graph is.
 *
 * <p>Labels are sorted and partitioned by index, so a label lands in exactly one
 * batch however the batches fall. Each batch is then grouped by Candidate and sent
 * as one call per group, because a Packet means one Candidate, and every synthesised
 * Packet is number-redacted at send time — gap fill builds its own payload, so it
 * would otherwise bypass the redaction a prepared Packet already carries (ADR 0008:
 * amounts never leave the database).
 */
final class LabelGapFiller {

    static final int BATCH_SIZE = 40;

    private final ClassifierLlm llm;
    private final Map<String, LayerBLineJudgment> answers = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    LabelGapFiller(ClassifierLlm llm) {
        this.llm = Objects.requireNonNull(llm, "llm");
    }

    /** One representative cell per label, and the label it stands for. */
    record Queued(QualifiedLabel label, PacketCell cell, PacketCell labelCell, long candidateId) {}

    /** Ask for every label not already answered. Returns the answers by label key. */
    Map<String, LayerBLineJudgment> fill(
            List<Queued> queued, OntologySlice slice, LayerAJudgment layerA) {
        Map<String, Queued> representatives = new LinkedHashMap<>();
        for (Queued item : queued) {
            representatives.putIfAbsent(item.label().key(), item);
        }
        List<Queued> distinct = new ArrayList<>(representatives.values());
        distinct.sort(Comparator.comparing(item -> item.label().key()));

        for (int start = 0; start < distinct.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, distinct.size());
            Map<Long, List<Queued>> byCandidate = new LinkedHashMap<>();
            for (Queued item : distinct.subList(start, end)) {
                byCandidate.computeIfAbsent(item.candidateId(), id -> new ArrayList<>()).add(item);
            }
            for (List<Queued> group : byCandidate.values()) {
                ask(group, slice, layerA);
            }
        }
        return Map.copyOf(answers);
    }

    List<String> failures() {
        return List.copyOf(failures);
    }

    private void ask(List<Queued> group, OntologySlice slice, LayerAJudgment layerA) {
        Packet packet = PacketRedactor.redact(synthesise(group), false);
        List<LayerBLineJudgment> lines;
        try {
            lines = llm.classifyLayerB(new LayerBPrompt(packet, slice, layerA, null));
        } catch (RuntimeException e) {
            // A group the model cannot answer costs that group's names, not the run.
            failures.add("label gap fill candidate " + group.get(0).candidateId()
                    + ": " + e.getMessage());
            return;
        }
        if (lines == null) {
            return;
        }
        Map<String, Queued> byCoord = new LinkedHashMap<>();
        for (Queued item : group) {
            byCoord.put(item.cell().coord(), item);
        }
        for (LayerBLineJudgment line : lines) {
            Queued item = byCoord.get(line.coord());
            if (item == null) {
                continue;
            }
            answers.putIfAbsent(item.label().key(), line);
        }
    }

    /** One Packet per Candidate: the representative cells plus the labels naming them. */
    private static Packet synthesise(List<Queued> group) {
        LinkedHashSet<PacketCell> cells = new LinkedHashSet<>();
        for (Queued item : group) {
            cells.add(item.cell());
            if (item.labelCell() != null) {
                cells.add(item.labelCell());
            }
        }
        Queued first = group.get(0);
        return new Packet(
                first.candidateId(),
                first.cell().cellId(),
                first.cell().worksheetId(),
                "child",
                List.copyOf(cells),
                List.of(),
                true);
    }

    /** A synthetic label cell so the model sees the name it is being asked about. */
    static PacketCell labelCellFor(PacketCell amount, String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        return new PacketCell(
                -amount.cellId(),
                amount.worksheetId(),
                labelCoord(amount),
                amount.rowNum(),
                Math.max(1, amount.colNum() - 1),
                PacketCell.ROLE_CONTEXT,
                "string",
                label,
                label,
                null,
                null,
                false,
                false);
    }

    private static String labelCoord(PacketCell amount) {
        return "LBL" + amount.rowNum() + "_" + amount.colNum();
    }

    /** Normalised label key, so callers and the cache agree on identity. */
    static String keyOf(String groupLabel, String memberLabel) {
        return new QualifiedLabel(
                groupLabel == null ? "" : OntologySlice.normalize(groupLabel),
                memberLabel == null ? "" : OntologySlice.normalize(memberLabel)).key();
    }
}
