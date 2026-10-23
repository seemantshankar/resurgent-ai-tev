package com.resurgent.tev.doc.induce;

import com.resurgent.tev.doc.pack.DocTypePack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Frozen document-ontology spine leaves (Architecture §6.5) used by pack induction.
 * Label matching is deterministic: normalize → exact leaf-name match → alias match.
 * New industry leaves may only sit under {@link DocTypePack#SPINE_ROOTS}.
 */
public final class SpineCatalog {

    /** One frozen (or well-known) spine leaf. */
    public record SpineLeaf(String path, String pathRoot, List<String> labels) {
        public SpineLeaf {
            path = path == null ? "" : path.trim();
            pathRoot = pathRoot == null ? "" : pathRoot.toLowerCase(Locale.ROOT);
            labels = labels == null ? List.of() : List.copyOf(labels);
        }

        public String leafName() {
            int sep = path.lastIndexOf(" > ");
            return sep < 0 ? path : path.substring(sep + 3);
        }
    }

    private static final List<SpineLeaf> LEAVES = List.of(
            new SpineLeaf("identity > Legal Name", "identity",
                    List.of("legal name", "name of the member", "name of member", "subject name",
                            "subject", "in the name of", "name", "seller name", "vendor name",
                            "promoter name", "company name", "party name")),
            new SpineLeaf("identity > CIN", "identity",
                    List.of("cin", "corporate identity number", "company identification number")),
            new SpineLeaf("identity > PAN", "identity",
                    List.of("pan", "permanent account number", "pan of the", "pan no", "pan number")),
            new SpineLeaf("identity > GSTIN", "identity",
                    List.of("gstin", "gst number", "gst no", "goods and services tax identification")),
            new SpineLeaf("identity > Constitution", "identity",
                    List.of("constitution", "constitution of firm", "entity type", "status of firm")),
            new SpineLeaf("identity > Registered Office", "identity",
                    List.of("registered office", "regd. office", "address", "registered address")),
            new SpineLeaf("identity > Directors", "identity",
                    List.of("directors", "director name", "board of directors")),
            new SpineLeaf("identity > Promoters", "identity",
                    List.of("promoters", "promoter", "partners", "partnership")),
            new SpineLeaf("identity > Shareholding %", "identity",
                    List.of("shareholding", "shareholding %", "shareholding pattern", "share %")),

            new SpineLeaf("economic > Promoter Net Worth", "economic",
                    List.of("promoter net worth", "net worth statement")),
            new SpineLeaf("economic > Promoter Net Worth > As Of", "economic",
                    List.of("as of", "as of date", "as on", "as at", "as on date", "as_of_date",
                            "as of the date")),
            new SpineLeaf("economic > Promoter Net Worth > Assets", "economic",
                    List.of("assets", "total assets", "gross assets", "assets total")),
            new SpineLeaf("economic > Promoter Net Worth > Liabilities", "economic",
                    List.of("liabilities", "total liabilities", "liabilities total", "loans and advances")),
            new SpineLeaf("economic > Promoter Net Worth > Net Worth", "economic",
                    List.of("net worth", "net_worth", "nw", "nw as on", "net worth of",
                            "tangible net worth", "परिसंपत्ति")),
            new SpineLeaf("economic > Project Cost", "economic",
                    List.of("project cost", "total project cost", "cost of project", "project cost (as per dpr)")),
            new SpineLeaf("economic > Project Cost > Subtotal", "economic",
                    List.of("subtotal", "sub total", "sub-total")),
            new SpineLeaf("economic > Project Cost > Tax", "economic",
                    List.of("tax", "gst", "cgst", "sgst", "igst", "taxes", "tax and duties")),
            new SpineLeaf("economic > Project Cost > Freight", "economic",
                    List.of("freight", "freight charges", "transportation", "transport charges")),
            new SpineLeaf("economic > Project Cost > Quoted Gross", "economic",
                    List.of("gross total", "grand total", "quoted gross", "total amount",
                            "total cost", "net payable", "payable amount")),
            new SpineLeaf("economic > Project Cost > Line Item", "economic",
                    List.of("line item", "line items", "item description", "description of work")),
            new SpineLeaf("economic > Means of Finance", "economic",
                    List.of("means of finance", "means of financing", "sources of funds")),
            new SpineLeaf("economic > Means of Finance > Equity", "economic",
                    List.of("equity", "promoter equity", "equity contribution")),
            new SpineLeaf("economic > Means of Finance > Debt", "economic",
                    List.of("debt", "term loan", "loan", "bank finance", "debt component")),
            new SpineLeaf("economic > Tariff", "economic",
                    List.of("tariff", "energy charge", "ppa rate")),
            new SpineLeaf("economic > Revenue basis", "economic",
                    List.of("revenue basis", "revenue", "sales basis", "offtake basis")),

            new SpineLeaf("technical > Capacity", "technical",
                    List.of("capacity", "contract capacity", "installed capacity", "capacity_mw",
                            "mw", "capacity (mw)")),
            new SpineLeaf("technical > Technology", "technical",
                    List.of("technology", "tech", "module type", "panel type")),
            new SpineLeaf("technical > COD", "technical",
                    List.of("cod", "commercial operation date", "commercial operations date",
                            "scheduled cod", "cod date")),
            new SpineLeaf("technical > Location", "technical",
                    List.of("location", "site", "project location", "site location",
                            "project name", "name of project")),
            new SpineLeaf("technical > Land area", "technical",
                    List.of("land area", "site area", "acreage", "acres")),
            new SpineLeaf("technical > PLF/CUF", "technical",
                    List.of("plf", "cuf", "plant load factor", "capacity utilization factor")),
            new SpineLeaf("technical > Yield", "technical",
                    List.of("yield", "cuf yield", "generation yield", "specific yield")),
            new SpineLeaf("technical > Fuel/Water requirement", "technical",
                    List.of("fuel requirement", "water requirement", "fuel", "water")),

            new SpineLeaf("commercial > Offtake > Seller", "commercial",
                    List.of("seller", "generator", "seller name", "wind power generator",
                            "supplier", "vendor")),
            new SpineLeaf("commercial > Offtake > Buyer", "commercial",
                    List.of("buyer", "procurer", "licensee", "buyer name", "purchaser",
                            "offtaker", "contracting authority")),
            new SpineLeaf("commercial > Offtake > Tariff", "commercial",
                    List.of("tariff", "ppa rate", "energy charge", "contract tariff", "levellised tariff")),
            new SpineLeaf("commercial > Offtake > Tenor", "commercial",
                    List.of("tenor", "term", "period of agreement", "tenure", "contract period",
                            "agreement period")),
            new SpineLeaf("commercial > Offtake term > Validity", "commercial",
                    List.of("validity", "validity date", "quote validity", "offer validity",
                            "valid up to", "valid till")),
            new SpineLeaf("commercial > Offtake term > Payment", "commercial",
                    List.of("payment terms", "payment term", "terms of payment", "payment")),

            new SpineLeaf("legal > Governing law", "legal",
                    List.of("governing law", "jurisdiction", "applicable law", "law applicable")),

            new SpineLeaf("regulatory > Approval", "regulatory",
                    List.of("approval", "regulatory approval", "permit", "sanction", "consent")),

            new SpineLeaf("frame > Document Title > Date", "frame",
                    List.of("date", "document date", "quote date", "quotation date",
                            "agreement date", "certificate date", "issue date", "dated")),
            new SpineLeaf("frame > Signature Block > Name", "frame",
                    List.of("issuer name", "authorized signatory", "signatory name",
                            "certified by", "signature", "name of the ca", "auditor name")),
            new SpineLeaf("frame > Signature Block > Membership", "frame",
                    List.of("membership", "membership no", "membership number",
                            "issuer membership", "m. no", "m.no", "frn")),

            // Debt syndication and credit analysis. Aliases stay specific where a
            // shorter word is already a TEV leaf ("tenor" remains offtake tenor).
            new SpineLeaf("identity > Promoter Qualification", "identity",
                    List.of("promoter qualification", "educational qualification",
                            "academic qualification", "qualification")),
            new SpineLeaf("identity > Promoter Experience", "identity",
                    List.of("promoter experience", "years of experience", "experience")),
            new SpineLeaf("identity > Management Qualification", "identity",
                    List.of("management qualification", "director qualification")),
            new SpineLeaf("identity > Management Experience", "identity",
                    List.of("management experience", "director experience", "career history")),

            new SpineLeaf("economic > Credit Rating", "economic",
                    List.of("credit rating", "instrument rating", "rating")),
            new SpineLeaf("economic > Rating Outlook", "economic",
                    List.of("rating outlook", "outlook")),
            new SpineLeaf("economic > Rated Facility", "economic",
                    List.of("rated facility", "rated facilities", "facility schedule")),
            new SpineLeaf("economic > Facility Tranche", "economic",
                    List.of("facility tranche", "debt tranche", "tranche")),
            new SpineLeaf("economic > Facility Pricing", "economic",
                    List.of("facility pricing", "pricing", "interest rate", "coupon")),
            new SpineLeaf("economic > Facility Tenor", "economic",
                    List.of("facility tenor", "loan tenor", "door to door tenor")),
            new SpineLeaf("economic > Facility Moratorium", "economic",
                    List.of("moratorium", "moratorium period", "principal moratorium")),
            new SpineLeaf("economic > Facility Repayment", "economic",
                    List.of("repayment", "repayment schedule", "repayment terms")),
            new SpineLeaf("economic > Average Bank Balance", "economic",
                    List.of("average bank balance", "average monthly balance", "abb")),
            new SpineLeaf("economic > Cheque Bounce", "economic",
                    List.of("cheque bounce", "inward bounce", "outward bounce", "bounce")),
            new SpineLeaf("economic > Limit Utilization", "economic",
                    List.of("limit utilization", "fund based utilization", "utilization")),

            new SpineLeaf("legal > Facility Security", "legal",
                    List.of("facility security", "primary security", "collateral security",
                            "security package")),
            new SpineLeaf("legal > Facility Covenant", "legal",
                    List.of("facility covenant", "financial covenant", "covenant", "covenants")),

            new SpineLeaf("regulatory > CIBIL Score", "regulatory",
                    List.of("cibil score", "cibil", "credit score")),
            new SpineLeaf("regulatory > CMR Rank", "regulatory",
                    List.of("cmr rank", "commercial credit rank", "cmr")),
            new SpineLeaf("regulatory > Days Past Due", "regulatory",
                    List.of("days past due", "dpd")),
            new SpineLeaf("regulatory > SMA Status", "regulatory",
                    List.of("sma status", "special mention account", "sma")),
            new SpineLeaf("regulatory > Wilful Defaulter", "regulatory",
                    List.of("wilful defaulter", "willful defaulter", "wilful default",
                            "willful default")));

    private static final Map<String, SpineLeaf> BY_NORMALIZED_LABEL = indexLabels();

    private SpineCatalog() {}

    private static Map<String, SpineLeaf> indexLabels() {
        Map<String, SpineLeaf> index = new LinkedHashMap<>();
        for (SpineLeaf leaf : LEAVES) {
            index.putIfAbsent(normalize(leaf.leafName()), leaf);
            for (String label : leaf.labels()) {
                index.putIfAbsent(normalize(label), leaf);
            }
        }
        return index;
    }

    /** All known frozen spine leaves, in declaration order. */
    public static List<SpineLeaf> leaves() {
        return LEAVES;
    }

    /** Every spine path string (leaf paths only). */
    public static Set<String> paths() {
        Set<String> out = new LinkedHashSet<>();
        for (SpineLeaf leaf : LEAVES) {
            out.add(leaf.path());
        }
        return out;
    }

    /**
     * True when {@code path} is a known frozen spine leaf (exact, case-insensitive).
     */
    public static boolean isSpinePath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String n = normalize(path);
        for (SpineLeaf leaf : LEAVES) {
            if (normalize(leaf.path()).equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code root} is one of the closed {@link DocTypePack#SPINE_ROOTS}. */
    public static boolean isKnownRoot(String root) {
        if (root == null || root.isBlank()) {
            return false;
        }
        return DocTypePack.SPINE_ROOTS.contains(root.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * Maps a printed label (or path fragment) onto a frozen spine leaf.
     * Order: exact path → exact label → exact leaf name → longest contained label (≥ 4 chars).
     */
    public static Optional<SpineLeaf> matchLabel(String label) {
        if (label == null || label.isBlank()) {
            return Optional.empty();
        }
        String n = normalize(label);
        // exact path match first (commercial > Offtake > Tariff ≠ economic > Tariff)
        for (SpineLeaf leaf : LEAVES) {
            if (normalize(leaf.path()).equals(n)) {
                return Optional.of(leaf);
            }
        }
        SpineLeaf exact = BY_NORMALIZED_LABEL.get(n);
        if (exact != null) {
            return Optional.of(exact);
        }
        // try the last path segment of a proposed path
        int sep = n.lastIndexOf(" > ");
        if (sep >= 0) {
            SpineLeaf segment = BY_NORMALIZED_LABEL.get(n.substring(sep + 3).trim());
            if (segment != null) {
                return Optional.of(segment);
            }
        }
        // longest contained label
        SpineLeaf best = null;
        int bestLen = 0;
        for (Map.Entry<String, SpineLeaf> e : BY_NORMALIZED_LABEL.entrySet()) {
            String key = e.getKey();
            if (key.length() < 4 || key.length() <= bestLen) {
                continue;
            }
            if (n.equals(key) || n.contains(key) || (key.contains(n) && n.length() >= 4)) {
                best = e.getValue();
                bestLen = key.length();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Resolves a candidate path to a spine leaf path, or empty when it is not a spine leaf.
     * Accepts either a bare label or a full {@code root > ... > leaf} path.
     */
    public static Optional<String> toSpinePath(String pathOrLabel) {
        return matchLabel(pathOrLabel).map(SpineLeaf::path);
    }

    /** Normalizes a label for matching: lower-case, strip punctuation, collapse spaces. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).trim();
        s = s.replace('&', ' ');
        s = s.replaceAll("[_\\-.\\,/()\\[\\]{}:;'\"`%]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    /** Label stems that are meaningful field tokens even without a spine hit. */
    public static List<String> labelStems() {
        List<String> stems = new ArrayList<>();
        for (SpineLeaf leaf : LEAVES) {
            stems.add(leaf.leafName());
            stems.addAll(leaf.labels());
        }
        return List.copyOf(stems);
    }
}
