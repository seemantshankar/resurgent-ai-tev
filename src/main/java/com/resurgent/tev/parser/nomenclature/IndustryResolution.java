package com.resurgent.tev.parser.nomenclature;

/**
 * How a mandate's industry pack was chosen. Missing industry never blocks a
 * slice: the catalog infers a stub and asks for one confirm.
 */
public record IndustryResolution(
        String industryTag,
        boolean confirmed,
        boolean inferred) {

    public static final String UNSPECIFIED = "unspecified";

    public static IndustryResolution unspecified() {
        return new IndustryResolution(UNSPECIFIED, false, true);
    }

    public static IndustryResolution inferred(String industryTag) {
        return new IndustryResolution(industryTag, false, true);
    }

    public static IndustryResolution confirmed(String industryTag) {
        return new IndustryResolution(industryTag, true, false);
    }

    public boolean needsConfirm() {
        return !confirmed;
    }
}
