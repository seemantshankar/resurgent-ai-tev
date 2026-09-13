package com.resurgent.tev.parser.nomenclature;

/** Synonym that points at an existing leaf rather than creating a new parent. */
public record NomenclatureAlias(String aliasText, String leafPath) {}
