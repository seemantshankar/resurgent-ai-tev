package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.nomenclature.OntologySlice;

/**
 * The label behind a naming question: the group's label plus the member's, scoped
 * to the worksheet the cell sits on. Candidate, chunk, column and coordinate stay
 * excluded (ADR 0019) — they are the axes along which one line used to get
 * different answers. Worksheet stays included: Case I / Case II sheets repeat the
 * same labels for different lines, and a question about a cell must be asked in
 * that cell's own sheet context (ADR 0021).
 */
record QualifiedLabel(long worksheetId, String groupLabel, String memberLabel) {

    QualifiedLabel {
        groupLabel = groupLabel == null ? "" : groupLabel;
        memberLabel = memberLabel == null ? "" : memberLabel;
    }

    String key() {
        String group = OntologySlice.normalize(groupLabel);
        String member = OntologySlice.normalize(memberLabel);
        String line = group.isEmpty() ? member : group + OntologySlice.SEPARATOR + member;
        return worksheetId + "|" + line;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QualifiedLabel label && key().equals(label.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }

    @Override
    public String toString() {
        return key();
    }
}
