package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.discover.Packet;
import com.resurgent.tev.parser.discover.PacketCell;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Splits large Layer B binding jobs into bounded amount chunks so truncation and
 * retries stay local to each chunk (#122 Phase 2).
 *
 * <p>Retained one release as the fallback for sheets the cell graph cannot type —
 * the per-cell Layer B prompt still runs, and only fills cells the graph left
 * unbound (ADR 0019). Chunking is exactly the axis along which two cells sharing a
 * label used to get different answers, which is why naming moved to one question per
 * qualified label. Delete this once the graph path has held for a release.
 */
final class LayerBJobSplitter {

    static final int MAX_AMOUNTS_PER_CHUNK = 40;

    private LayerBJobSplitter() {}

    static List<LayerBPrompt> chunks(LayerBPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        List<PacketCell> amounts = LayerBAmountSupport.amountCells(prompt.packet());
        if (amounts.size() <= MAX_AMOUNTS_PER_CHUNK) {
            return List.of(prompt);
        }
        List<PacketCell> nonAmounts = new ArrayList<>();
        Set<Long> amountIds = new HashSet<>();
        for (PacketCell amount : amounts) {
            amountIds.add(amount.cellId());
        }
        for (PacketCell cell : prompt.packet().cells()) {
            if (!amountIds.contains(cell.cellId())) {
                nonAmounts.add(cell);
            }
        }
        List<LayerBPrompt> out = new ArrayList<>();
        for (int start = 0; start < amounts.size(); start += MAX_AMOUNTS_PER_CHUNK) {
            int end = Math.min(start + MAX_AMOUNTS_PER_CHUNK, amounts.size());
            List<PacketCell> chunkCells = new ArrayList<>(nonAmounts.size() + (end - start));
            chunkCells.addAll(nonAmounts);
            chunkCells.addAll(amounts.subList(start, end));
            Packet chunkPacket = new Packet(
                    prompt.packet().candidateId(),
                    prompt.packet().parseRunId(),
                    prompt.packet().worksheetId(),
                    prompt.packet().candidateKind(),
                    List.copyOf(chunkCells),
                    prompt.packet().largeRangeRefs(),
                    prompt.packet().contextClosureSucceeded());
            out.add(new LayerBPrompt(
                    chunkPacket,
                    prompt.ontologySlice(),
                    prompt.layerA(),
                    prompt.parentDisposition()));
        }
        return List.copyOf(out);
    }
}
