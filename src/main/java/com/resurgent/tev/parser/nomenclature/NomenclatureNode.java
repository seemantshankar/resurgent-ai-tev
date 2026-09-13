package com.resurgent.tev.parser.nomenclature;

/**
 * One controlled-vocabulary node: a frozen spine mid-level, an industry leaf,
 * or a mandate overlay soft leaf.
 */
public record NomenclatureNode(
        String path,
        String name,
        String parentPath,
        String layer,
        boolean frozen,
        boolean leaf,
        String industryTag,
        Long mandateId) {

    public static final String LAYER_SPINE = "spine";
    public static final String LAYER_INDUSTRY = "industry";
    public static final String LAYER_MANDATE_SOFT = "mandate_soft";
}
