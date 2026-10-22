package com.resurgent.tev.doc.assess;

import com.resurgent.tev.doc.compare.Comparison;
import com.resurgent.tev.doc.compare.Comparisons;
import com.resurgent.tev.doc.compare.Verdict;
import com.resurgent.tev.doc.db.ConflictRepository;
import com.resurgent.tev.doc.db.ConflictRepository.ConflictFactRow;
import com.resurgent.tev.doc.db.ConflictRepository.ConflictWrite;
import com.resurgent.tev.doc.db.FmConflictSources;
import com.resurgent.tev.doc.extract.MoneyNormalizer;
import com.resurgent.tev.doc.extract.RateNormalizer;
import com.resurgent.tev.doc.llm.DocLlmClient;
import com.resurgent.tev.parser.classify.KindTokens;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The M3-12/M3-13 conflict step of the {@code assess_conflicts} stage (issues #145,
 * #146, ADR 0026): finds the mandate's data points stated by more than one document
 * or by more than one section (chapter passage) of the same document, compares the
 * statements through the one {@link Comparisons} module, and persists one
 * {@code conflict} row per genuine disagreement — both sides in full, never
 * overwritten. A disagreement is only formed when the two statements are of the same
 * kind (two rates, two amounts, two numbers in the same unit), within the same entity
 * and site scope, and about the same period: a different as-on date makes them
 * different readings, not a conflict, and differing free text (names) never forms a
 * row at all. A pair inside one document version is comparable only when both facts
 * are passage-linked and the passages differ — two sections of one document
 * (kind {@code intra-doc}); a document-level fact carries no section. A money fact the
 * bind stage never settled (issue #153) has no shared path a model cell could meet it
 * on, and a bound money amount may simply have no cell in the model: both are recorded
 * as skips naming what is missing, never dropped from the comparison in silence. A fact
 * no binder ever ran over stores no path at all, which the skip row keeps as NULL.
 *
 * <p>Settlement follows ADR 0026's tiers: the versioned {@link PrecedenceRules} first,
 * else AI judgement through the {@link LlmConflictSettlement} seam (confident → settled
 * and flagged as settled by AI; declining or offline → the row stays open and the
 * checklist shows the item Conflicting). A disagreement of the same document type on
 * two different document dates is a revision, not a conflict: it is stored as
 * {@code versions-differ} with no settlement — M4 chooses the winner.
 */
public final class ConflictScan {

    /**
     * Field keys whose fact states the period its document's readings are about
     * (the net-worth certificate's "as on 31/03/2025"), used to decide whether two
     * statements are about the same period at all.
     */
    private static final Set<String> PERIOD_FIELD_KEYS = Set.of(
            "as_of_date", "as_on", "financial_year", "financial_years", "period");

    private ConflictScan() {}

    /**
     * One conflict row as written: the row id and the write behind it.
     *
     * @param conflictId the stored {@code conflict_id}
     * @param write the persisted row
     */
    public record Written(long conflictId, ConflictWrite write) {}

    /**
     * Finds and stores the mandate's conflicts and revisions. The caller owns the
     * transaction and clears the previous rows first.
     *
     * @param connection workspace connection (caller owns the transaction)
     * @param mandateId mandate scope
     * @param assessmentRunId the {@code assess_conflicts} run the rows cite, or null
     * @param client document LLM seam for the AI tier, or null when offline
     * @param usageSink receives every settlement call's {@link DocLlmClient.Usage}
     * @return the rows written, in fact order
     * @throws SQLException if any statement fails
     */
    public static List<Written> scan(Connection connection, long mandateId, Long assessmentRunId,
            DocLlmClient client, Consumer<DocLlmClient.Usage> usageSink) throws SQLException {
        ConflictRepository repository = new ConflictRepository(connection);
        List<ConflictFactRow> facts = repository.factsForMandate(mandateId);
        Map<String, List<ConflictFactRow>> groups = groups(facts);
        Map<Long, String> periodsByVersion = periodsByVersion(facts);

        List<Written> written = new ArrayList<>();
        for (List<ConflictFactRow> group : groups.values()) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    ConflictFactRow left = group.get(i);
                    ConflictFactRow right = group.get(j);
                    if (!comparable(left, right, periodsByVersion)) {
                        continue;
                    }
                    Comparison comparison = compare(left, right);
                    if (comparison == null || !comparison.differs()) {
                        continue;
                    }
                    ConflictWrite write = writeFor(left, right, comparison, periodsByVersion,
                            client, usageSink);
                    long conflictId = repository.insertConflict(mandateId, assessmentRunId, write);
                    written.add(new Written(conflictId, write));
                    if (ConflictRepository.KIND_VERSIONS_DIFFER.equals(write.kind())
                            && write.leftFactId() != null && write.rightFactId() != null) {
                        markFactSuperseded(connection, write.rightFactId(), write.leftFactId());
                    }
                }
            }
        }
        scanFm(connection, mandateId, assessmentRunId, facts, periodsByVersion,
                client, usageSink, repository, written);
        return written;
    }

    private static void markFactSuperseded(Connection connection, long supersededFactId,
            long winningFactId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE fact SET is_current = 0, superseded_by_fact_id = ? WHERE fact_id = ?")) {
            ps.setLong(1, winningFactId);
            ps.setLong(2, supersededFactId);
            ps.executeUpdate();
        }
    }

    /**
     * Whether this mandate has a financial model at all: cells read by the Excel side,
     * bound or not. The document pipeline never writes them, so any row here is a model.
     */
    private static boolean hasFinancialModel(Connection connection, long mandateId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM cell_interpretation ci"
                        + " JOIN parse_run p ON p.parse_run_id = ci.parse_run_id"
                        + " WHERE p.mandate_id = ? LIMIT 1")) {
            ps.setLong(1, mandateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void scanFm(Connection connection, long mandateId, Long runId,
            List<ConflictFactRow> facts, Map<Long, String> periods, DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink, ConflictRepository repository,
            List<Written> written) throws SQLException {
        Map<String, List<FmConflictSources.Cell>> paths =
                FmConflictSources.forMandate(connection, mandateId);
        if (paths.isEmpty() && !hasFinancialModel(connection, mandateId)) {
            // Nothing was ever read as this mandate's model: there is no comparison to
            // refuse, and a skip would cite a model that does not exist. A model whose
            // cells proved no path is still a model — its refusals stay recorded.
            return;
        }
        for (ConflictFactRow fact : facts) {
            if (!isFmComparableKind(fact.valueKind())) {
                continue;
            }
            if (!"bound".equals(fact.nomenclatureStatus())) {
                // A fact the bind stage never settled has no shared path a model
                // cell could meet it on. Plain "unbound" with no reason is the insert
                // default rather than a bind decision, so the reason says which of the
                // two this was. The fact stays retrievable; it is never compared silently.
                if (fact.ontologyPath() == null) {
                    repository.insertConflictSkip(mandateId, runId, fact, null,
                            "no shared path yet: the doc-bind stage never bound this fact");
                } else {
                    repository.insertConflictSkip(mandateId, runId, fact, null,
                            "unbound fact: no shared nomenclature path names '"
                                    + fact.ontologyPath() + "' ("
                                    + (fact.unboundReason() == null ? "doc-bind stage never ran"
                                            : "bind stage: " + fact.unboundReason()) + ")");
                }
                continue;
            }
            List<FmConflictSources.Cell> cells = paths.get(fact.ontologyPath());
            if (cells == null || cells.isEmpty()) {
                // A bound amount the model says nothing about is a comparison that was
                // possible and refused, not one that agreed. Silent here is the green-but-
                // blind read #147 forbids, so the model's silence is recorded per amount.
                repository.insertConflictSkip(mandateId, runId, fact, null,
                        "financial model has no cell bound to " + fact.ontologyPath());
                continue;
            }
            // Layer B also binds row labels. They identify the amount, not a second value.
            List<FmConflictSources.Cell> numeric = cells.stream()
                    .filter(c -> c.value().numericValue() != null || c.value().formulaText() != null)
                    .toList();
            if (!numeric.isEmpty()) {
                cells = numeric;
            }
            if (cells.size() != 1) {
                String sources = String.join(", ", cells.stream().map(FmConflictSources.Cell::source).toList());
                repository.insertConflictSkip(mandateId, runId, fact, sources,
                        "ambiguous FM path: multiple bound cells for " + fact.ontologyPath());
                continue;
            }
            FmConflictSources.Cell cell = cells.get(0);
            if (cell.periods().stream().anyMatch(p -> "ambiguous".equals(p.resolution()))) {
                repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                        "ambiguous FM period: " + cell.periods().stream()
                                .map(p -> p.text() == null ? p.normalized() : p.text()).toList());
                continue;
            }
            String periodSkip = fmPeriodSkip(cell, periodOf(fact, periods));
            if (periodSkip != null) {
                repository.insertConflictSkip(mandateId, runId, fact, cell.source(), periodSkip);
                continue;
            }
            var reading = cell.reading();
            if (reading == null || reading.refusal() != null) {
                repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                        "no usable reading for document comparison"
                                + (reading != null && reading.refusal() != null
                                        ? ": " + reading.refusal() : ""));
                continue;
            }
            if (!isKindCompatible(fact.valueKind(), reading.kind())) {
                repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                        "kind mismatch: document states " + fact.valueKind() + ", FM cell reading states " + reading.kind());
                continue;
            }

            Comparison compared;
            String modelValue;
            if ("money".equals(fact.valueKind())) {
                if (reading.absoluteAmount() == null) {
                    repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                            "no usable reading for document money comparison: missing absolute amount");
                    continue;
                }
                modelValue = (reading.currency() == null ? "" : reading.currency() + " ")
                        + reading.absoluteAmount();
                compared = Comparisons.money(valueOf(fact), modelValue);
            } else {
                Double fmNum = parseCellNumeric(cell);
                if (fmNum == null) {
                    repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                            "financial model cell carries no numeric value for " + fact.valueKind() + " comparison");
                    continue;
                }
                Double docNum = fact.valueNumber();
                if (docNum == null) {
                    repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                            "document fact carries no numeric value for " + fact.valueKind() + " comparison");
                    continue;
                }
                String docNormUnit = normalizeUnitToken(fact.unit());
                String fmNormUnit = normalizeUnitToken(reading.unit());

                if (!Objects.equals(docNormUnit, fmNormUnit)) {
                    repository.insertConflictSkip(mandateId, runId, fact, cell.source(),
                            "unit_mismatch_no_conversion: document states "
                                    + (docNormUnit == null ? "unstated" : docNormUnit)
                                    + ", FM states "
                                    + (fmNormUnit == null ? "unstated" : fmNormUnit));
                    continue;
                }
                String unitSuffix = reading.unit() != null && !reading.unit().isBlank() ? " " + reading.unit() : "";
                modelValue = MoneyNormalizer.plain(fmNum) + unitSuffix;
                String cmpUnit = docNormUnit != null ? docNormUnit : "unit";
                compared = Comparisons.quantity(docNum, cmpUnit, fmNum, cmpUnit);
            }

            if (!compared.differs()) {
                continue;
            }
            String settlement = ConflictRepository.SETTLEMENT_OPEN;
            String chosen = null;
            String ruleId = null;
            String rulesVersion = null;
            String reasoning = "no precedence rule covers this disagreement and AI judgement did not"
                    + " settle it (declined or unavailable)";
            var rule = PrecedenceRules.settle(fact.docTypeCode(), "financial-model");
            if (rule.isPresent()) {
                settlement = ConflictRepository.SETTLEMENT_RULE;
                chosen = rule.get().choseLeft() ? "left" : "right";
                ruleId = rule.get().ruleId();
                rulesVersion = rule.get().rulesVersion();
                reasoning = rule.get().reason();
            } else {
                var ai = LlmConflictSettlement.settle(client, dataPointOf(fact), describe(fact),
                        "financial model cell " + cell.source() + " states " + modelValue, usageSink);
                if (ai.isPresent()) {
                    settlement = ConflictRepository.SETTLEMENT_AI;
                    chosen = ai.get().choseLeft() ? "left" : "right";
                    reasoning = ai.get().reasoning();
                }
            }
            ConflictWrite write = new ConflictWrite(ConflictRepository.KIND_DOC_FM,
                    dataPointOf(fact), fact.fieldKey(), fact.ontologyPath(), fact.entityId(),
                    fact.siteId(), periodOf(fact, periods), compared.verdict().wireName(),
                    fact.factId(), fact.documentVersionId(), fact.pageIndex(), valueOf(fact),
                    fact.quote(), null, null, null, modelValue, cell.source(), settlement,
                    chosen, ruleId, rulesVersion, reasoning);
            written.add(new Written(repository.insertConflict(mandateId, runId, write), write));
        }
    }

    /**
     * Returns whether the fact kind is eligible for comparison against the financial model.
     */
    private static boolean isFmComparableKind(String kind) {
        return "money".equals(kind) || "quantity".equals(kind) || "rate".equals(kind) || "percent".equals(kind);
    }

    /**
     * Checks compatibility between fact kind and financial model reading kind.
     */
    private static boolean isKindCompatible(String factKind, String readingKind) {
        if (factKind == null || readingKind == null) {
            return false;
        }
        return switch (factKind) {
            case "money" -> "money".equalsIgnoreCase(readingKind);
            case "quantity" -> "quantity".equalsIgnoreCase(readingKind) || "count".equalsIgnoreCase(readingKind);
            case "rate" -> "rate".equalsIgnoreCase(readingKind) || "ratio".equalsIgnoreCase(readingKind);
            case "percent" -> "percent".equalsIgnoreCase(readingKind) || "ratio".equalsIgnoreCase(readingKind);
            default -> false;
        };
    }

    /**
     * Normalizes a unit token to standard canonical form (e.g., % or kWp).
     */
    private static String normalizeUnitToken(String unit) {
        if (unit == null || unit.isBlank()) {
            return null;
        }
        return KindTokens.normalizeUnit(unit.trim());
    }

    /**
     * Parses numeric value from a financial model cell.
     */
    private static Double parseCellNumeric(FmConflictSources.Cell cell) {
        if (cell.reading() != null) {
            Double amount = parseDoubleOrNull(cell.reading().absoluteAmount());
            if (amount != null) {
                return amount;
            }
        }
        if (cell.value() != null) {
            Double num = parseDoubleOrNull(cell.value().numericValue());
            if (num != null) {
                return num;
            }
            return parseDoubleOrNull(cell.value().textValue());
        }
        return null;
    }

    /**
     * Safely parses a double from a string, returning null if invalid or blank.
     */
    private static Double parseDoubleOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Never infer a calendar period from upload dates or from the document's value. */
    private static String fmPeriodSkip(FmConflictSources.Cell cell, String documentPeriod) {
        List<String> modelPeriods = cell.periods().stream()
                .filter(p -> !"missing".equals(p.resolution()))
                .map(p -> p.normalized() == null ? p.text() : p.normalized())
                .filter(Objects::nonNull).distinct().toList();
        if (modelPeriods.isEmpty() && documentPeriod == null) {
            return null;
        }
        if (modelPeriods.size() != 1 || documentPeriod == null) {
            return "ambiguous FM period: cannot align " + modelPeriods
                    + " with document period " + documentPeriod;
        }
        String modelPeriod = modelPeriods.get(0);
        if (documentPeriod.trim().equalsIgnoreCase(modelPeriod.trim())) {
            return null;
        }
        Comparison comparison = Comparisons.date(documentPeriod, modelPeriod);
        if (comparison.verdict() == Verdict.AGREE) {
            return null;
        }
        return "different periods or incompatible precision: document " + documentPeriod
                + " vs FM " + modelPeriod + " (" + comparison.detail() + ")";
    }

    /** Facts grouped by data-point identity: pack field key, else ontology path, per line. */
    private static Map<String, List<ConflictFactRow>> groups(List<ConflictFactRow> facts) {
        Map<String, List<ConflictFactRow>> groups = new LinkedHashMap<>();
        for (ConflictFactRow fact : facts) {
            String identity = identityOf(fact);
            if (identity == null) {
                continue;
            }
            groups.computeIfAbsent(identity, key -> new ArrayList<>()).add(fact);
        }
        return groups;
    }

    /**
     * The data point a fact states: its pack field key when bound by key, else its
     * ontology path; a row fact keeps its line key. A fact with neither is never
     * compared — free text alone is not a data point.
     */
    private static String identityOf(ConflictFactRow fact) {
        String base = fact.fieldKey() != null ? "field:" + fact.fieldKey()
                : fact.ontologyPath() != null ? "path:" + fact.ontologyPath()
                        : null;
        if (base == null) {
            return null;
        }
        return base + (fact.lineKey() != null ? "|line:" + fact.lineKey() : "");
    }

    /** The stated period of each document version, from its period-role or period-keyed facts. */
    private static Map<Long, String> periodsByVersion(List<ConflictFactRow> facts) {
        Map<Long, String> periods = new LinkedHashMap<>();
        for (ConflictFactRow fact : facts) {
            if (fact.fieldKey() == null
                    || !PERIOD_FIELD_KEYS.contains(fact.fieldKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String text = fact.valueText() != null && !fact.valueText().isBlank()
                    ? fact.valueText()
                    : fact.valueDate();
            if (text != null && !text.isBlank()) {
                periods.putIfAbsent(fact.documentVersionId(), text);
            }
        }
        return periods;
    }

    /**
     * Whether two facts can even be about the same thing: different sources (two
     * documents, or two different sections of one document), the same entity and site
     * scope, and a compatible stated period — a different as-on date (or financial
     * year) makes them different readings, never a conflict.
     */
    private static boolean comparable(ConflictFactRow left, ConflictFactRow right,
            Map<Long, String> periodsByVersion) {
        if (!differentSources(left, right)) {
            return false;
        }
        if (!Objects.equals(left.entityId(), right.entityId())
                || !Objects.equals(left.siteId(), right.siteId())) {
            return false;
        }
        String leftPeriod = periodOf(left, periodsByVersion);
        String rightPeriod = periodOf(right, periodsByVersion);
        if (leftPeriod == null || rightPeriod == null) {
            return true;
        }
        Comparison comparison = Comparisons.date(leftPeriod, rightPeriod);
        if (comparison.verdict() == Verdict.DIFFERENT_READINGS) {
            return false;
        }
        return comparison.verdict() != Verdict.NOT_COMPARABLE
                || leftPeriod.trim().equalsIgnoreCase(rightPeriod.trim());
    }

    /**
     * Two different sources: facts from two document versions, or two facts of one
     * version read from two different passages (sections) of it. A fact with no
     * passage carries no section, and two statements of one section are the same
     * statement — neither can contradict the other.
     */
    private static boolean differentSources(ConflictFactRow left, ConflictFactRow right) {
        if (left.documentVersionId() != right.documentVersionId()) {
            return true;
        }
        return left.passageId() != null && right.passageId() != null
                && !left.passageId().equals(right.passageId());
    }

    private static String periodOf(ConflictFactRow fact, Map<Long, String> periodsByVersion) {
        String stated = fact.periodEvidence();
        if (stated != null && !stated.isBlank()) {
            return stated;
        }
        return periodsByVersion.get(fact.documentVersionId());
    }

    /**
     * The comparator ladder: rates (each side must state its unit — differing units
     * refuse the pair rather than alarm), else money, else structured numbers in the
     * same stated unit. Dates are never a disagreement and names are never compared —
     * both return null, i.e. no row, so free text can never false-alarm.
     *
     * @return the comparison to act on, or null when the pair cannot disagree
     */
    private static Comparison compare(ConflictFactRow left, ConflictFactRow right) {
        String leftText = left.valueText();
        String rightText = right.valueText();
        if (leftText != null && rightText != null) {
            RateNormalizer.Result leftRate = RateNormalizer.parse(leftText);
            RateNormalizer.Result rightRate = RateNormalizer.parse(rightText);
            if (leftRate.valueNumber() != null && leftRate.unit() != null
                    && rightRate.valueNumber() != null && rightRate.unit() != null) {
                Comparison comparison = Comparisons.rate(leftText, rightText);
                // Differing rate units (kWh vs percent) are not a disagreement.
                return comparison.verdict() == Verdict.NOT_COMPARABLE ? null : comparison;
            }
            MoneyNormalizer.Result leftMoney = MoneyNormalizer.parse(leftText);
            MoneyNormalizer.Result rightMoney = MoneyNormalizer.parse(rightText);
            if (leftMoney.ok() && rightMoney.ok()) {
                Comparison comparison = Comparisons.money(leftText, rightText);
                // Differing stated currencies are not a disagreement.
                return comparison.verdict() == Verdict.NOT_COMPARABLE ? null : comparison;
            }
        }
        if (left.valueNumber() != null && right.valueNumber() != null
                && left.unit() != null && right.unit() != null) {
            Comparison comparison = Comparisons.quantity(left.valueNumber(), left.unit(),
                    right.valueNumber(), right.unit());
            // Differing or unstated units are not a disagreement (no conversion).
            return comparison.verdict() == Verdict.NOT_COMPARABLE ? null : comparison;
        }
        return null;
    }

    /**
     * The row to store for one disagreeing pair: a same-type, differently-dated pair is
     * a revision ({@code versions-differ}, left side the newest, no settlement); anything
     * else — two documents ({@code doc-doc}) or two sections of one document
     * ({@code intra-doc}) — settles by precedence rule, else by AI judgement, else
     * stays open.
     */
    private static ConflictWrite writeFor(ConflictFactRow left, ConflictFactRow right,
            Comparison comparison, Map<Long, String> periodsByVersion, DocLlmClient client,
            Consumer<DocLlmClient.Usage> usageSink) {
        if (versionsDiffer(left, right)) {
            ConflictFactRow newest = newer(left, right);
            ConflictFactRow older = newest == left ? right : left;
            String reasoning = String.format(Locale.ROOT,
                    "same document type (%s) on different document dates (%s vs %s):"
                            + " newer version supersedes older version under version precedence ladder",
                    newest.docTypeCode(), newerDate(left, right), olderDate(left, right));
            return write(ConflictRepository.KIND_VERSIONS_DIFFER, newest, older, comparison,
                    periodsByVersion, ConflictRepository.SETTLEMENT_RULE, "left",
                    "version_precedence_v1", "1.0", reasoning);
        }

        // Two sections of one document are the same document type by definition, so a
        // precedence rule can never cover them — they settle by AI judgement or stay open.
        String kind = left.documentVersionId() == right.documentVersionId()
                ? ConflictRepository.KIND_INTRA_DOC
                : ConflictRepository.KIND_DOC_DOC;
        String dataPoint = dataPointOf(left);
        Optional<PrecedenceRules.Decision> decision =
                PrecedenceRules.settle(left.docTypeCode(), right.docTypeCode());
        if (decision.isPresent()) {
            PrecedenceRules.Decision settled = decision.get();
            String reasoning = String.format(Locale.ROOT,
                    "precedence rule %s (rules %s): %s", settled.ruleId(),
                    settled.rulesVersion(), settled.reason());
            return write(kind, left, right, comparison,
                    periodsByVersion, ConflictRepository.SETTLEMENT_RULE,
                    settled.choseLeft() ? "left" : "right", settled.ruleId(),
                    settled.rulesVersion(), reasoning);
        }

        Optional<LlmConflictSettlement.Verdict> ai = LlmConflictSettlement.settle(client,
                dataPoint, describe(left), describe(right), usageSink);
        if (ai.isPresent()) {
            return write(kind, left, right, comparison,
                    periodsByVersion, ConflictRepository.SETTLEMENT_AI,
                    ai.get().choseLeft() ? "left" : "right", null, null, ai.get().reasoning());
        }
        return write(kind, left, right, comparison,
                periodsByVersion, ConflictRepository.SETTLEMENT_OPEN, null, null, null,
                "no precedence rule covers this disagreement and AI judgement did not"
                        + " settle it (declined or unavailable)");
    }

    /** A revision: distinct versions of the same type, both dated, with different dates. */
    private static boolean versionsDiffer(ConflictFactRow left, ConflictFactRow right) {
        // One version cannot be its own revision, even when its date has a precision
        // the date comparator cannot interpret (for example canonical YYYY-MM).
        if (left.documentVersionId() == right.documentVersionId()) {
            return false;
        }
        if (left.docTypeCode() == null || !left.docTypeCode().equals(right.docTypeCode())) {
            return false;
        }
        if (left.documentDate() == null || right.documentDate() == null) {
            return false;
        }
        return Comparisons.date(left.documentDate(), right.documentDate()).verdict()
                != Verdict.AGREE;
    }

    /** Canonical document dates are ISO-like, so the newest orders first by text. */
    private static ConflictFactRow newer(ConflictFactRow left, ConflictFactRow right) {
        return left.documentDate().compareTo(right.documentDate()) >= 0 ? left : right;
    }

    /**
     * Returns the newer document date between left and right rows.
     */
    private static String newerDate(ConflictFactRow left, ConflictFactRow right) {
        return newer(left, right).documentDate();
    }

    /**
     * Returns the older document date between left and right rows.
     */
    private static String olderDate(ConflictFactRow left, ConflictFactRow right) {
        return newer(left, right) == left ? right.documentDate() : left.documentDate();
    }

    private static ConflictWrite write(String kind, ConflictFactRow left, ConflictFactRow right,
            Comparison comparison, Map<Long, String> periodsByVersion, String settlement,
            String chosenSide, String ruleId, String rulesVersion, String reasoning) {
        String leftPeriod = periodOf(left, periodsByVersion);
        String rightPeriod = periodOf(right, periodsByVersion);
        String period = leftPeriod != null && rightPeriod != null ? leftPeriod : null;
        return new ConflictWrite(kind, dataPointOf(left), left.fieldKey(), left.ontologyPath(),
                left.entityId(), left.siteId(), period, comparison.verdict().wireName(),
                left.factId(), left.documentVersionId(), left.pageIndex(), leftValue(left),
                left.quote(), right.factId(), right.documentVersionId(), right.pageIndex(),
                rightValue(right), right.quote(), settlement, chosenSide, ruleId, rulesVersion,
                reasoning);
    }

    private static String dataPointOf(ConflictFactRow fact) {
        return fact.fieldKey() != null ? fact.fieldKey() : fact.ontologyPath();
    }

    private static String leftValue(ConflictFactRow fact) {
        return valueOf(fact);
    }

    private static String rightValue(ConflictFactRow fact) {
        return valueOf(fact);
    }

    /** The value as stated for the conflict side (never blank — the fact states one). */
    private static String valueOf(ConflictFactRow fact) {
        if (fact.valueText() != null && !fact.valueText().isBlank()) {
            return fact.valueText();
        }
        if (fact.valueNumber() != null) {
            return MoneyNormalizer.plain(fact.valueNumber()) + (fact.unit() != null ? " " + fact.unit() : "");
        }
        return fact.valueDate() == null ? "" : fact.valueDate();
    }

    /**
     * One side's description for the AI tier: document, page, chapter section when
     * passage-linked, stated value and quote. For an intra-document conflict both
     * sides name the same document but their own section and page, so the reasoning
     * can cite which section of the document each value was read from.
     */
    private static String describe(ConflictFactRow fact) {
        StringBuilder text = new StringBuilder();
        text.append(fact.fileName() == null ? "document" : fact.fileName());
        if (fact.pageIndex() != null) {
            text.append(" page ").append(fact.pageIndex());
        }
        if (fact.passageHeading() != null && !fact.passageHeading().isBlank()) {
            text.append(", chapter \"").append(fact.passageHeading()).append('"');
        }
        text.append(" states ").append(valueOf(fact));
        if (fact.quote() != null && !fact.quote().isBlank()) {
            text.append(" (\"").append(fact.quote().replaceAll("\\s+", " ").trim()).append("\")");
        }
        if (fact.docTypeCode() != null) {
            text.append(" [").append(fact.docTypeCode()).append("]");
        }
        return text.toString();
    }
}
