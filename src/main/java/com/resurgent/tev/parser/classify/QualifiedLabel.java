package com.resurgent.tev.parser.classify;

import com.resurgent.tev.parser.nomenclature.OntologySlice;
import java.util.Objects;

/**
 * The cache key for one naming question: the group's own label plus the member's.
 * Deliberately excludes candidate, chunk, column and coordinate — those are exactly
 * the axes along which the same line got different answers from one prompt to the
 * next, so two cells sharing a label can no longer diverge.
 */
record QualifiedLabel(String groupLabel, String memberLabel) {

    QualifiedLabel {
        groupLabel = groupLabel == null ? "" : groupLabel;
        memberLabel = memberLabel == null ? "" : memberLabel;
    }

    String key() {
        String group = OntologySlice.normalize(groupLabel);
        String member = OntologySlice.normalize(memberLabel);
        return group.isEmpty() ? member : group + OntologySlice.SEPARATOR + member;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof QualifiedLabel that && key().equals(that.key());
    }

    @Override
    public int hashCode() {
        return Objects.hash(key());
    }
}
