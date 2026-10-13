package com.resurgent.tev.doc.extract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** HTML-table → GFM conversion and footer cleanup from LlamaParse markdown. */
class ParseMarkdownTest {

    @Test
    void htmlTableBecomesGfmPipeTable() {
        String html = """
                <table>
                  <tr><th>Legal Name</th><td>BHAGYANAGAR SOLAR PRIVATE LIMITED</td></tr>
                  <tr><th>Trade Name</th><td>BHAGYANAGAR SOLAR PRIVATE LIMITED</td></tr>
                </table>
                """;
        String md = ParseMarkdown.clean(html);
        assertThat(md).doesNotContain("<table");
        assertThat(md).contains("| Legal Name |");
        assertThat(md).contains("| BHAGYANAGAR SOLAR PRIVATE LIMITED |");
        assertThat(md).contains("|---|---|");
        assertThat(md.lines().filter(l -> l.startsWith("| Legal Name")).count()).isEqualTo(1);
        assertThat(md).contains("| Trade Name |");
    }

    @Test
    void firstRowIsHeaderAndNotDuplicated() {
        String html = """
                <table>
                  <tr><td>No</td><td>Name</td></tr>
                  <tr><td>1</td><td>T R VENKATARAMANAN</td></tr>
                  <tr><td>2</td><td>MAHIPALREDDY N</td></tr>
                </table>
                """;
        String md = ParseMarkdown.clean(html);
        assertThat(md).contains("| No | Name |");
        assertThat(md).contains("| 1 | T R VENKATARAMANAN |");
        assertThat(md).contains("| 2 | MAHIPALREDDY N |");
        assertThat(md.lines().filter(l -> l.contains("No | Name")).count()).isEqualTo(1);
    }

    @Test
    void colspanWidensRow() {
        String html = """
                <table>
                  <tr><td colspan="3"><b>Signature</b></td></tr>
                  <tr><td>Name</td><td>Value</td><td>Extra</td></tr>
                </table>
                """;
        String md = ParseMarkdown.clean(html);
        // first row is header; second is body
        assertThat(md).contains("| Signature |  |  |");
        assertThat(md).contains("| Name | Value | Extra |");
    }

    @Test
    void raggedRowsMergeOverflowAndNeverInventColumns() {
        // GST REG-06 style: header row has 2 cells, one row splits a date range
        // across 4 cells — must not pad the header with colN names.
        String html = """
                <table>
                  <tr><td>Legal Name</td><td>BHAGYANAGAR SOLAR PRIVATE LIMITED</td></tr>
                  <tr><td>Date of Liability</td><td></td></tr>
                  <tr><td>Period of Validity</td><td>From</td><td>21/05/2026</td><td>To</td><td>Not Applicable</td></tr>
                </table>
                """;
        String md = ParseMarkdown.clean(html);
        assertThat(md).doesNotContain("col4");
        assertThat(md).doesNotContain("col5");
        assertThat(md).contains(
                "| Period of Validity | From 21/05/2026 To Not Applicable |");
        assertThat(md).contains("| Date of Liability |  |");
        assertThat(md.lines().filter(l -> l.startsWith("|---")).count()).isEqualTo(1);
    }

    @Test
    void brBecomesSpaceAndTagsStripped() {
        String html = """
                <table>
                  <tr><td><b>Floor No.:</b> 2ND FLOOR<br /><b>PIN Code:</b> 500003</td></tr>
                </table>
                """;
        String md = ParseMarkdown.clean(html);
        assertThat(md).contains("Floor No.: 2ND FLOOR");
        assertThat(md).contains("PIN Code: 500003");
        assertThat(md).doesNotContain("<b>");
    }

    @Test
    void pageFootersRemoved() {
        String md = ParseMarkdown.clean("""
                # Title

                Body text.

                12

                More text.
                """);
        assertThat(md).contains("Body text.");
        assertThat(md).contains("More text.");
        assertThat(md).doesNotContain("\n12\n");
    }

    @Test
    void htmlEntitiesUnescaped() {
        String md = ParseMarkdown.clean("<table><tr><td>A &#x26; B</td></tr></table>");
        assertThat(md).contains("A & B");
    }

    @Test
    void pageMarkersRemoved() {
        String md = ParseMarkdown.clean("""
                <!-- page 1 -->
                # Title

                Body text.

                <!-- Page 2 of 16 -->

                More text.
                """);
        assertThat(md).contains("Body text.");
        assertThat(md).contains("More text.");
        assertThat(md).doesNotContain("<!--");
        assertThat(md).doesNotContain("page 1");
    }

    @Test
    void qrAndSecurityNoiseRemovedButRealDataKept() {
        String md = ParseMarkdown.clean("""
                PAN AAOCB3072G, GSTIN 36AAOCB3072G1ZM.
                Penalty ofupto Rs. 10,000. Rule 114B of Income Tax Rules, 1962.
                Amount 1,00,00,000 and date 21/05/2026 and PIN 500003.
                taxpayer. 2212104147)506112102555731140261405,51341,310,4214,1 HC3$CF
                311434419616-$-21211044)13330131144,196244114B,122 Possessing
                F10,000542112141125112111 205237852212012104412012134231,1
                """);
        assertThat(md).contains("AAOCB3072G");
        assertThat(md).contains("36AAOCB3072G1ZM");
        assertThat(md).contains("10,000");
        assertThat(md).contains("1,00,00,000");
        assertThat(md).contains("21/05/2026");
        assertThat(md).contains("500003");
        assertThat(md).doesNotContain("2212104147)506112102555731140261405");
        assertThat(md).doesNotContain("311434419616-$-21211044");
        assertThat(md).doesNotContain("F10,000542112141125112111");
        assertThat(md).doesNotContain("205237852212012104412012134231");
    }

    @Test
    void tableCellsAreNotTreatedAsSecurityNoise() {
        String md = ParseMarkdown.clean("""
                | Field | Value |
                |---|---|
                | Account | 12345678901234567890 |
                """);
        assertThat(md).contains("12345678901234567890");
    }

    @Test
    void plainMarkdownPassesThrough() {
        String src = "# Hello\n\nWorld\n";
        assertThat(ParseMarkdown.clean(src).trim()).isEqualTo("# Hello\n\nWorld");
    }

    @Test
    void wordJoinSplitsRejoinInsideTableCells() {
        String md = ParseMarkdown.clean("""
                | Annapur na Traders | Khandw a | Electroni c clearing system |
                |---|---|---|
                | Motilal Gopikish an | Satyanar an Mahesh wari | Mayur Industrie s |
                """);
        assertThat(md).contains("Khandwa");
        assertThat(md).contains("Electronic clearing system");
        assertThat(md).contains("Motilal Gopikishan");
        assertThat(md).contains("Satyanaran Maheshwari");
        assertThat(md).contains("Mayur Industries");
        assertThat(md).doesNotContain("Khandw a");
        assertThat(md).doesNotContain("Electroni c");
    }

    @Test
    void splitPanRejoinsOnlyWhenPanShaped() {
        String md = ParseMarkdown.clean("""
                | Name | PAN |
                |---|---|
                | Manish Jain | ARHPJ9494 P |
                | Someone | ABCDE1234 X |
                | Atul | AABHA297 7D |
                """);
        assertThat(md).contains("ARHPJ9494P");
        assertThat(md).contains("ABCDE1234X");
        assertThat(md).contains("AABHA2977D");
    }

    @Test
    void danglingDateLabelDroppedButRealDateKept() {
        String md = ParseMarkdown.clean("""
                ** UDIN : 25405926BMNRKD9980 **
                **DATE :
                **PLACE : Khandwa**
                Date : 22/09/2025
                """);
        assertThat(md).contains("22/09/2025");
        assertThat(md).contains("25405926BMNRKD9980");
        assertThat(md.lines().anyMatch(l -> l.trim().equals("**DATE :"))).isFalse();
    }

    @Test
    void danglingDateCellEmptiedButValuedCellsKept() {
        String md = ParseMarkdown.clean("""
                | **UDIN : 25405926BMNRKD9980** |
                |---|
                | **DATE : |
                | **PLACE : Khandwa** |
                """);
        assertThat(md).contains("25405926BMNRKD9980");
        assertThat(md).contains("Khandwa");
        assertThat(md).doesNotContain("DATE :");
    }

    @Test
    void placeholderOnlyRowDroppedButValuedRowsKept() {
        String md = ParseMarkdown.clean("""
                **CA LALIT PATEL** | **ANAND MAHESHWARI**
                |![seal: FIRM](page_27_seal_1_v2.jpg) | ![signature: Proprietor](page_27_image_1_v2.jpg)
                **DATE : |
                **PLACE : Khandwa** |
                """);
        assertThat(md).contains("LALIT PATEL");
        assertThat(md).contains("Khandwa");
        assertThat(md).doesNotContain("page_27_seal_1_v2.jpg");
        assertThat(md).doesNotContain("DATE :");
    }

    @Test
    void qrAndImagePlaceholdersDropped() {
        String md = ParseMarkdown.clean("""
                # Title

                ![seal: AGARWAL PATEL & SINHAL](page_2_seal_1_v2.jpg)
                ![signature: Anand Maheshwari](page_28_image_1_v2.jpg)
                code: QR code

                Body text.
                """);
        assertThat(md).contains("Body text.");
        assertThat(md).doesNotContain("page_2_seal_1_v2.jpg");
        assertThat(md).doesNotContain("code: QR code");
    }
}
