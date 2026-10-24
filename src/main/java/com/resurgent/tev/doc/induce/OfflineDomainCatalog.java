package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.induce.PackMiningClient.MinedClause;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedColumn;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedField;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedPassage;
import com.resurgent.tev.doc.induce.PackMiningClient.MinedTable;
import com.resurgent.tev.doc.induce.PackMiningClient.MiningDocument;
import com.resurgent.tev.doc.induce.PackMiningClient.MiningRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic document-type shapes used when web search is not allowed.
 * Templates name domain standards (rating, bureau, bank statement, promoter
 * profile); they never copy a client name or amount.
 */
final class OfflineDomainCatalog {

    private OfflineDomainCatalog() {}

    /** Tables, clauses, passages, and date kind implied by the sanitized text. */
    static Supplement match(MiningRequest request) {
        String text = StructuralFingerprint.webQuery(
                request.documents() == null ? List.of() : request.documents(),
                request.industryTag());
        List<String> docIds = new ArrayList<>();
        if (request.documents() != null) {
            for (MiningDocument document : request.documents()) {
                if (document.id() != null && !document.id().isBlank()) {
                    docIds.add(document.id());
                }
            }
        }
        List<String> ids = List.copyOf(docIds);
        if (text.contains("term sheet") || text.contains("information memorandum")
                || text.contains("debt structuring")) {
            return debtStructuring(ids);
        }
        if (text.contains("vendor proposal")) {
            return vendorProposal(ids);
        }
        if (text.contains("credit rating") || text.contains("rated facility")
                || text.contains("rating outlook")) {
            return creditRating(ids, text);
        }
        if (text.contains("cibil") || text.contains("wilful defaulter")
                || text.contains("willful defaulter") || text.contains("cmr")) {
            return bureau(ids);
        }
        if (text.contains("bank statement") || text.contains("average bank balance")
                || text.contains("cheque bounce")) {
            return bankStatement(ids);
        }
        if (text.contains("qualification") || text.contains("promoter experience")
                || text.contains("director profile") || text.contains("resume")) {
            return promoterProfile(ids);
        }
        return Supplement.none(text);
    }

    private static Supplement creditRating(List<String> docIds, String query) {
        return new Supplement(
                List.of(table("rated_facilities", "economic > Rated Facility", "economic",
                        "facility", "borrower", "site", null,
                        List.of(column("facility", "text"), column("borrower", "text"),
                                column("site", "text"), column("tenor", "text"),
                                column("pricing", "text")),
                        docIds)),
                List.of(new MinedClause("financial_covenant", "Financial Covenant", "covenant",
                        List.of("Financial Covenant"), docIds, true)),
                List.of(new MinedPassage("rating_rationale", List.of("Rating Rationale"),
                        List.of("credit_rating"), docIds, true)),
                "issue",
                query,
                "Offline domain standard for a credit rating report: rating, outlook, "
                        + "rated facilities, and covenants.",
                List.of(
                        extra("rating_agency", "economic > Rating Agency", "economic",
                                "Rating Agency", docIds),
                        extra("long_term_rating", "economic > Long Term Rating", "economic",
                                "Long Term Rating", docIds),
                        extra("short_term_rating", "economic > Short Term Rating", "economic",
                                "Short Term Rating", docIds)));
    }

    private static Supplement debtStructuring(List<String> docIds) {
        return new Supplement(
                List.of(table("facility_tranches", "economic > Facility Tranche", "economic",
                        "tranche", null, null, null,
                        List.of(column("tranche", "text"), column("limit", "text"),
                                column("pricing", "text"), column("tenor", "text"),
                                column("moratorium", "text"), column("repayment", "text")),
                        docIds)),
                List.of(new MinedClause("financial_covenant", "Financial Covenant", "covenant",
                        List.of("Financial Covenant"), docIds, true)),
                List.of(),
                "issue",
                null,
                "Offline domain standard for a debt structuring term sheet.",
                List.of());
    }

    private static Supplement vendorProposal(List<String> docIds) {
        return new Supplement(
                List.of(table("boq_lines", "economic > Project Cost > Line Item", "economic",
                        "description", null, null, null,
                        List.of(column("description", "text"), column("qty", "text"),
                                column("rate", "text"), column("amount", "text")),
                        docIds)),
                List.of(),
                List.of(),
                "issue",
                null,
                "Offline domain standard for a vendor proposal.",
                List.of());
    }

    private static Supplement bureau(List<String> docIds) {
        return new Supplement(
                List.of(table("bureau_accounts", "regulatory > CIBIL Score", "regulatory",
                        "account_type", null, null, null,
                        List.of(column("account_type", "text"), column("dpd", "text"),
                                column("sma", "text")),
                        docIds)),
                List.of(),
                List.of(new MinedPassage("delinquency", List.of("Delinquency", "Account Information"),
                        List.of("bureau_accounts"), docIds, true)),
                "issue",
                null,
                "Offline domain standard for a credit bureau report.",
                List.of());
    }

    private static Supplement bankStatement(List<String> docIds) {
        return new Supplement(
                List.of(table("monthly_activity", "economic > Average Bank Balance", "economic",
                        "metric", null, null, "month",
                        List.of(column("metric", "text"), column("month", "text"),
                                column("value", "text")),
                        docIds)),
                List.of(),
                List.of(new MinedPassage("account_conduct", List.of("Account Conduct", "Summary"),
                        List.of("monthly_activity"), docIds, true)),
                "date",
                null,
                "Offline domain standard for a bank statement.",
                List.of());
    }

    private static Supplement promoterProfile(List<String> docIds) {
        return new Supplement(
                List.of(table("qualifications", "identity > Promoter Qualification", "identity",
                        "qualification", null, null, null,
                        List.of(column("qualification", "text"), column("year", "text")),
                        docIds)),
                List.of(),
                List.of(new MinedPassage("career", List.of("Experience", "Career"),
                        List.of("qualifications"), docIds, true)),
                "date",
                null,
                "Offline domain standard for a promoter or director profile.",
                List.of());
    }

    private static MinedField extra(
            String key, String path, String root, String label, List<String> docIds) {
        return new MinedField(key, path, root, "text", false, label,
                List.of(label), List.of(label), docIds, List.of(), true);
    }

    private static MinedTable table(
            String name, String path, String root, String keyColumn, String entityKey,
            String siteKey, String periodKey, List<MinedColumn> columns, List<String> docIds) {
        return new MinedTable(name, path, root, keyColumn, entityKey, siteKey, periodKey,
                columns, docIds, true);
    }

    private static MinedColumn column(String key, String valueKind) {
        return new MinedColumn(key, valueKind);
    }

    /** Extra structure the offline miner should merge into a field-only result. */
    record Supplement(
            List<MinedTable> tables,
            List<MinedClause> clauses,
            List<MinedPassage> passages,
            String documentDateKind,
            String webSearchQuery,
            String domainRationale,
            List<MinedField> fields) {

        static Supplement none(String query) {
            return new Supplement(List.of(), List.of(), List.of(), null, query,
                    "Offline structural fingerprint; web search was not used.", List.of());
        }
    }
}
