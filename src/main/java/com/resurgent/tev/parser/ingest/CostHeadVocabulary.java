package com.resurgent.tev.parser.ingest;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Locked §11 cost-head aliases. Matching is normalized exact equality, never fuzzy or substring. */
public final class CostHeadVocabulary {
    private static final Map<String, String> CODE_BY_ALIAS = aliases();

    private CostHeadVocabulary() {}

    public static Optional<String> exactMatch(String label) {
        if (label == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CODE_BY_ALIAS.get(normalize(label)));
    }

    private static Map<String, String> aliases() {
        Map<String, String> result = new LinkedHashMap<>();
        add(result, "LAND", "land", "land cost", "land & site", "site cost");
        add(result, "SITE_DEVELOPMENT", "site development", "land development", "site preparation");
        add(result, "CIVIL", "civil", "civil works", "building", "construction", "civil & structural");
        add(result, "PLUMBING", "plumbing", "sanitary");
        add(result, "FIRE_FIGHTING", "fire fighting", "fire protection", "fire system");
        add(result, "PLANT_MACHINERY", "plant & machinery", "p&m", "machinery");
        add(result, "KITCHEN_EQUIPMENT", "kitchen", "kitchen equipment", "kitchen/store");
        add(result, "WATER_TREATMENT", "water treatment", "wtp", "effluent", "etp", "stp");
        add(result, "LIFTS", "lift", "lifts", "elevator", "elevators");
        add(result, "HVAC", "hvac", "air conditioning", "ventilation");
        add(result, "ELECTRICAL", "electrical", "electrification", "wiring", "transformer", "panel");
        add(result, "GENERATOR", "dg set", "dg", "generator", "genset");
        add(result, "LED_LIGHTING", "led", "lighting");
        add(result, "MISC_EQUIPMENT", "furniture", "it", "computer", "office equipment");
        add(result, "PRE_OPERATIVE", "pre-operative", "preoperative", "preliminary", "pre-op");
        add(result, "WORKING_CAPITAL", "working capital", "margin money");
        add(result, "CONTINGENCY", "contingency");
        return Map.copyOf(result);
    }

    private static void add(Map<String, String> target, String code, String... aliases) {
        for (String alias : aliases) {
            target.put(normalize(alias), code);
        }
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
