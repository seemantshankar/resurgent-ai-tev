package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellPacketView;
import java.util.List;

/** Everything known about one sheet-qualified cell address in a parse run. */
public record CellMeaning(
        String qualifiedCoord,
        CellPacketView cell,
        List<CandidateRow> candidates,
        List<PacketDisposition> dispositions,
        NomenclatureBinding nomenclatureBinding,
        List<BindingPeer> peers,
        List<ProjectFactBinding> projectFacts,
        CellInterpretation interpretation,
        List<InterpretationEvidence> evidence) {

    public CellMeaning {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public CellMeaning(
            String qualifiedCoord,
            CellPacketView cell,
            List<CandidateRow> candidates,
            List<PacketDisposition> dispositions,
            NomenclatureBinding nomenclatureBinding,
            List<BindingPeer> peers,
            List<ProjectFactBinding> projectFacts,
            CellInterpretation interpretation) {
        this(
                qualifiedCoord,
                cell,
                candidates,
                dispositions,
                nomenclatureBinding,
                peers,
                projectFacts,
                interpretation,
                List.of());
    }

    public CellMeaning(
            String qualifiedCoord,
            CellPacketView cell,
            List<CandidateRow> candidates,
            List<PacketDisposition> dispositions,
            NomenclatureBinding nomenclatureBinding,
            List<BindingPeer> peers,
            List<ProjectFactBinding> projectFacts) {
        this(
                qualifiedCoord,
                cell,
                candidates,
                dispositions,
                nomenclatureBinding,
                peers,
                projectFacts,
                null,
                List.of());
    }
}
