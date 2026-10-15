package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.db.EntityRepository;
import com.resurgent.tev.doc.db.FactRepository;
import com.resurgent.tev.doc.query.FactQuery;
import com.resurgent.tev.doc.query.FactRow;
import com.resurgent.tev.doc.query.MandateQa;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tags every mandate fact with the entity it is about (issue #136), after the
 * entities of {@link EntityScan} are known. The ladder is deterministic and
 * never guesses: a fact is tagged only when exactly one entity is named — by
 * its free-text {@code subject_entity} or by the evidence quotes — and stays
 * untagged with a recorded reason otherwise.
 *
 * <p>Re-running re-tags every fact from scratch ({@link EntityRepository#clearEntities}
 * detached the previous run first), so no tag or reason goes stale.
 */
public final class FactTagger {

    /** Tagged because the fact's {@code subject_entity} states the entity's name. */
    static final String REASON_SUBJECT = "the fact's subject names this entity";

    /** Tagged because exactly one entity is named in the evidence. */
    static final String REASON_EVIDENCE = "the entity is named in the fact's evidence";

    /** Untagged: no entity's name appears in the fact or its evidence. */
    static final String REASON_NO_MATCH = "no entity is named in the fact or its evidence";

    /** Untagged: more than one entity is named — never resolved by guessing. */
    static final String REASON_AMBIGUOUS = "several entities are named in the fact or its evidence";

    /** Untagged: the mandate has no identified entities at all. */
    static final String REASON_NO_ENTITIES = "the mandate has no identified entities";

    /**
     * Tag counters for {@code run_stat} and the stage detail.
     *
     * @param factsTagged mandate facts tagged with an entity
     * @param factsUntagged mandate facts left untagged (each with a reason)
     */
    public record Tally(int factsTagged, int factsUntagged) {}

    private FactTagger() {}

    /**
     * Tags the mandate's facts. Caller owns the transaction.
     *
     * @param connection open workspace connection
     * @param mandateId mandate scope
     * @return the tag counters
     * @throws SQLException if any read or update fails
     */
    public static Tally tag(Connection connection, long mandateId) throws SQLException {
        EntityRepository entities = new EntityRepository(connection);
        List<MandateQa.EntityRow> known = entities.entities(mandateId);
        List<FactRow> facts = new FactRepository(connection).query(FactQuery.forMandate(mandateId));

        int tagged = 0;
        for (FactRow fact : facts) {
            Decision decision = decide(fact, known);
            entities.updateFactEntity(fact.factId(), decision.entityId(), decision.reason());
            if (decision.entityId() != null) {
                tagged++;
            }
        }
        return new Tally(tagged, facts.size() - tagged);
    }

    /** One fact's tag decision: the entity id, or null with the reason why not. */
    record Decision(Long entityId, String reason) {

        static Decision tagged(long entityId, String reason) {
            return new Decision(entityId, reason);
        }

        static Decision untagged(String reason) {
            return new Decision(null, reason);
        }
    }

    /**
     * The tagging ladder: the fact's subject wording first, then the evidence
     * text. One named entity tags the fact; none or several leave it untagged
     * with the specific reason — the code never guesses between entities.
     */
    static Decision decide(FactRow fact, List<MandateQa.EntityRow> entities) {
        if (entities.isEmpty()) {
            return Decision.untagged(REASON_NO_ENTITIES);
        }
        String subject = fact.subjectEntity();
        if (subject != null && !subject.isBlank()) {
            List<MandateQa.EntityRow> named = namedIn(subject, entities);
            if (named.size() == 1) {
                return Decision.tagged(named.get(0).entityId(), REASON_SUBJECT);
            }
            if (named.size() > 1) {
                return Decision.untagged(REASON_AMBIGUOUS);
            }
        }
        List<MandateQa.EntityRow> named = namedIn(evidenceText(fact), entities);
        if (named.size() == 1) {
            return Decision.tagged(named.get(0).entityId(), REASON_EVIDENCE);
        }
        return Decision.untagged(named.size() > 1 ? REASON_AMBIGUOUS : REASON_NO_MATCH);
    }

    /** Every entity whose name (or one of its spellings) is stated in the text. */
    private static List<MandateQa.EntityRow> namedIn(String text,
            List<MandateQa.EntityRow> entities) {
        List<MandateQa.EntityRow> named = new ArrayList<>();
        for (MandateQa.EntityRow entity : entities) {
            if (statesName(text, entity)) {
                named.add(entity);
            }
        }
        return named;
    }

    private static boolean statesName(String text, MandateQa.EntityRow entity) {
        if (EntityNames.containsName(text, entity.name())) {
            return true;
        }
        for (MandateQa.AliasRow alias : entity.aliases()) {
            if (EntityNames.containsName(text, alias.alias())) {
                return true;
            }
        }
        return false;
    }

    /** The searchable fact text: every evidence verbatim plus the stated value. */
    private static String evidenceText(FactRow fact) {
        StringBuilder text = new StringBuilder();
        if (fact.valueText() != null) {
            text.append(fact.valueText()).append('\n');
        }
        for (FactRow.EvidenceRow evidence : fact.evidence()) {
            if (evidence.verbatim() != null) {
                text.append(evidence.verbatim()).append('\n');
            }
        }
        return text.toString();
    }
}
