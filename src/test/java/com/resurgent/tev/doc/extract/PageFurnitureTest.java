package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PageFurnitureTest {

    @Test
    void widenLabelsAreNotFurniture() {
        assertThat(PageFurniture.label("Nominee director")).isFalse();
        assertThat(PageFurniture.label("Former name")).isFalse();
        assertThat(PageFurniture.implausibleExtraKey("nominee_director")).isFalse();
        assertThat(PageFurniture.implausibleExtraKey("former_name")).isFalse();
        assertThat(PageFurniture.fieldKey("nominee_director")).isFalse();
        assertThat(PageFurniture.fieldKey("former_name")).isFalse();
    }

    @Test
    void letterheadKeysAreFurnitureOnEveryPack() {
        assertThat(PageFurniture.fieldKey("page")).isTrue();
        assertThat(PageFurniture.fieldKey("phone")).isTrue();
        assertThat(PageFurniture.fieldKey("our_offices_at")).isTrue();
        assertThat(PageFurniture.fieldKey("bank")).isTrue();
        assertThat(PageFurniture.fieldKey("branch")).isTrue();
        assertThat(PageFurniture.fieldKey("registered_office")).isFalse();
        assertThat(PageFurniture.fieldKey("place_of_issue")).isFalse();
        assertThat(PageFurniture.fieldKey("legal_name")).isFalse();
    }

    @Test
    void ocrSnowIsNotAPublishableExtra() {
        assertThat(PageFurniture.publishableExtra(
                "b_a", "b a", "2 6")).isFalse();
        assertThat(PageFurniture.publishableExtra(
                "gg_g_all", "gg g = all", "; Ss 2 ff => Ss Y")).isFalse();
        assertThat(PageFurniture.publishableExtra(
                "epee_eee_geet", "epee eee geet", "epee eee geet soaRregia")).isFalse();
        assertThat(PageFurniture.implausibleExtraKey(
                "5_6_bo_a_f_z")).isTrue();
        assertThat(PageFurniture.ocrSnow("foo sae &")).isTrue();
    }

    @Test
    void formNamesAndAmountsStayPublishable() {
        assertThat(PageFurniture.publishableExtra(
                "legal_name", "Legal Name", "BHAGYANAGAR SOLAR PRIVATE LIMITED"))
                .isTrue();
        assertThat(PageFurniture.publishableExtra(
                "city_town_village", "City / Town / Village", "Secunderabad"))
                .isTrue();
        assertThat(PageFurniture.printableValue("22,87,50,984")).isTrue();
        assertThat(PageFurniture.printableValue("AAOCB3072G")).isTrue();
        assertThat(PageFurniture.printableValue("HYDB16570B")).isTrue();
        assertThat(PageFurniture.printableValue("21/05/2026")).isTrue();
        assertThat(PageFurniture.publishableExtra("tan", "TAN", "HYDB16570B")).isTrue();
        assertThat(PageFurniture.publishableExtra("as_of", "as on", "08/01/2026")).isTrue();
        assertThat(PageFurniture.implausibleExtraKey("as_of")).isFalse();
        assertThat(PageFurniture.publishableExtra(
                "registered_office",
                "Registered Office",
                "2nd Floor, Surya Towers, S.P. Road, Secunderabad, Hyderabad- 500003, Telangana"))
                .isTrue();
    }

    @Test
    void billOfQuantitiesCellsAreSpecDumps() {
        assertThat(PageFurniture.specDump(
                "625 Wp & above  Rayzon / Goldi / Waaree /  1 Solar PV Module"))
                .isTrue();
        assertThat(PageFurniture.specDump("GWE/Solar/2026-27/025")).isFalse();
    }
}
