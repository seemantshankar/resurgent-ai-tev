package com.resurgent.tev.parser.ingest;

/** The closed Sprint 3a vocabulary for a classified region. */
public enum RegionType {
    COST_HEAD("cost_head"),
    VENDOR_BLOCK("vendor_block"),
    PNL("pnl"),
    BS("bs"),
    CASH_FLOW("cash_flow"),
    DEBT_SCHEDULE("debt_schedule"),
    MOF("mof"),
    CAPACITY("capacity"),
    UTILITY("utility"),
    TIMELINE("timeline"),
    VERTICAL_FORM("vertical_form"),
    SUPPORT("support"),
    SCRATCH("scratch"),
    UNKNOWN("unknown");

    private final String databaseValue;

    RegionType(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }
}
