package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.db.CandidateRow;
import com.resurgent.tev.parser.db.CellPacketView;
import java.util.List;

/**
 * Everything known about one sheet-qualified cell address in a parse run.
 *
 * <p>{@code cellType} is the graph's typing evidence — kind, scale and the scale's
 * provenance. A binding read back here reports whether its scale was stated, adopted,
 * or unstated, so the unit default is never mistaken for a confident claim.
 */
public record CellMeaning(
        String qualifiedCoord,
        CellPacketView cell,
        List<CandidateRow> candidates,
        List<PacketDisposition> dispositions,
        NomenclatureBinding nomenclatureBinding,
        List<BindingPeer> peers,
        List<ProjectFactBinding> projectFacts,
        CellInterpretation interpretation,
        List<InterpretationEvidence> evidence,
        List<FormulaAnnotation> formulaAnnotations,
        String formulaGloss,
        CellType cellType) {

    public CellMeaning {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        formulaAnnotations =
                formulaAnnotations == null ? List.of() : List.copyOf(formulaAnnotations);
    }

    public CellMeaning(
            String qualifiedCoord,
            CellPacketView cell,
            List<CandidateRow> candidates,
            List<PacketDisposition> dispositions,
            NomenclatureBinding nomenclatureBinding,
            List<BindingPeer> peers,
            List<ProjectFactBinding> projectFacts,
            CellInterpretation interpretation,
            List<InterpretationEvidence> evidence,
            List<FormulaAnnotation> formulaAnnotations,
            String formulaGloss) {
        this(
                qualifiedCoord,
                cell,
                candidates,
                dispositions,
                nomenclatureBinding,
                peers,
                projectFacts,
                interpretation,
                evidence,
                formulaAnnotations,
                formulaGloss,
                null);
    }

    public CellMeaning(
            String qualifiedCoord,
            CellPacketView cell,
            List<CandidateRow> candidates,
            List<PacketDisposition> dispositions,
            NomenclatureBinding nomenclatureBinding,
            List<BindingPeer> peers,
            List<ProjectFactBinding> projectFacts,
            CellInterpretation interpretation,
            List<InterpretationEvidence> evidence,
            List<FormulaAnnotation> formulaAnnotations) {
        this(
                qualifiedCoord,
                cell,
                candidates,
                dispositions,
                nomenclatureBinding,
                peers,
                projectFacts,
                interpretation,
                evidence,
                formulaAnnotations,
                interpretation == null ? null : interpretation.formulaGloss());
    }

    public CellMeaning(
            String qualifiedCoord,
            CellPacketView cell,
            List<CandidateRow> candidates,
            List<PacketDisposition> dispositions,
            NomenclatureBinding nomenclatureBinding,
            List<BindingPeer> peers,
            List<ProjectFactBinding> projectFacts,
            CellInterpretation interpretation,
            List<InterpretationEvidence> evidence) {
        this(
                qualifiedCoord,
                cell,
                candidates,
                dispositions,
                nomenclatureBinding,
                peers,
                projectFacts,
                interpretation,
                evidence,
                List.of(),
                interpretation == null ? null : interpretation.formulaGloss());
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
                List.of(),
                List.of(),
                interpretation == null ? null : interpretation.formulaGloss());
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
                List.of(),
                List.of(),
                null);
    }
}
