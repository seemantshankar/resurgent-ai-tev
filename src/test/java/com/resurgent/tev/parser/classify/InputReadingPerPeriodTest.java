package com.resurgent.tev.parser.classify;

import static org.assertj.core.api.Assertions.assertThat;

import com.resurgent.tev.parser.db.InterpretationCellView;
import org.junit.jupiter.api.Test;

/**
 * A column header that says "per day" names a period the quantity is spread over, not the thing
 * counted. It was read as a column of days, so "Generation - Units (kw) per day" typed a cell as a
 * quantity in "day", and every cell derived from it inherited that unit.
 */
class InputReadingPerPeriodTest {

    private static InterpretationCellView cell() {
        return new InterpretationCellView(1L, 1L, "C3", 3, 3, "number", null, "6.31", "6.31", null, null,
                null, null, null, null, false, null, false, false, null, null);
    }

    private static ReadingOutcome read(String rowLabel, String columnHeader) {
        return InputReading.type(cell(), rowLabel, columnHeader, null, "INR", null, new UnstatedScales());
    }

    @Test
    void aColumnPerDayIsNotAColumnOfDays() {
        ReadingOutcome outcome = read("Project Parameters", "Generation - Units (kw) per day P75");
        assertThat(outcome.typed() && "day".equals(outcome.unit)).isFalse();
        assertThat(outcome.unit).isNotEqualTo("day");
    }

    @Test
    void aSlashPeriodIsNotTheThingCountedEither() {
        assertThat(read("Parameters", "Units generated / day").unit).isNotEqualTo("day");
        assertThat(read("Parameters", "Cost per month").unit).isNotEqualTo("month");
    }

    @Test
    void aColumnOfDaysOrMonthsIsStillAQuantity() {
        ReadingOutcome days = read("Parameters", "Credit period (days)");
        assertThat(days.typed()).isTrue();
        assertThat(days.kind).isEqualTo(ReadingOutcome.QUANTITY);
        ReadingOutcome months = read("Parameters", "Tenor months");
        assertThat(months.kind).isEqualTo(ReadingOutcome.QUANTITY);
        assertThat(months.unit).isEqualTo("months");
    }
}
