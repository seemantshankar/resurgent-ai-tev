package com.resurgent.tev.parser.db;

import java.util.Locale;

/**
 * What an {@code llm_usage.cost_usd} amount is counted in. LlamaCloud reports credits and never a
 * dollar figure, so a row holding credits is only usable as USD once the configured credit price is
 * applied to it — which is why the unit travels on the row instead of being guessed from the model
 * id (#152).
 */
public enum CostUnit {
    USD("usd"),
    CREDITS("credits");

    private final String wireName;

    CostUnit(String wireName) {
        this.wireName = wireName;
    }

    /** The value stored in {@code llm_usage.cost_unit}. */
    public String wireName() {
        return wireName;
    }

    /** The unit a stored row declares. */
    public static CostUnit fromWire(String wireName) {
        String wanted = wireName == null ? "" : wireName.toLowerCase(Locale.ROOT);
        for (CostUnit unit : values()) {
            if (unit.wireName.equals(wanted)) {
                return unit;
            }
        }
        throw new IllegalArgumentException("unknown cost unit " + wireName);
    }

    /**
     * USD for an amount recorded in this unit.
     *
     * @param usdPerCredit configured LlamaCloud credit price, ignored for a USD amount
     */
    public double toUsd(double amount, double usdPerCredit) {
        return this == CREDITS ? amount * usdPerCredit : amount;
    }
}
