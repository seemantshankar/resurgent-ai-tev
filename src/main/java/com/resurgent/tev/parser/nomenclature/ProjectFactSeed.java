package com.resurgent.tev.parser.nomenclature;

import java.util.List;

/** Identity and ops fact paths — never under {@code Project Cost}. */
final class ProjectFactSeed {

    static final List<ProjectFactField> FIELDS = List.of(
            new ProjectFactField("Project Identity > Legal Name", "Legal Name"),
            new ProjectFactField("Project Identity > Constitution / Entity Type", "Constitution"),
            new ProjectFactField("Project Identity > Partners / Promoters", "Partners"),
            new ProjectFactField("Project > Site / Location", "Site"),
            new ProjectFactField("Project > Installed Capacity", "Capacity"),
            new ProjectFactField("Project > Power Connection", "Power"));

    private ProjectFactSeed() {}
}
