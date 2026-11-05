package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The ranges of the consumption of water (HAS-317): where each starts and ends, the one before it, its buckets
class ConsumptionRangeTest {

    //a Thursday
    private static final LocalDate AT = LocalDate.of(2026, 11, 5);

    @Test
    void should_make_a_day_of_hours() {
        final LocalDateTime start = ConsumptionRange.DAY.start(AT);

        assertThat(start).isEqualTo(LocalDateTime.of(2026, 11, 5, 0, 0));
        assertThat(ConsumptionRange.DAY.end(start)).isEqualTo(LocalDateTime.of(2026, 11, 6, 0, 0));
        assertThat(ConsumptionRange.DAY.previous(start)).isEqualTo(LocalDateTime.of(2026, 11, 4, 0, 0));
        assertThat(ConsumptionRange.DAY.getUnit()).isEqualTo("hour");
    }

    @Test
    void should_make_a_week_from_monday_to_sunday() {
        final LocalDateTime start = ConsumptionRange.WEEK.start(AT);

        assertThat(start).isEqualTo(LocalDateTime.of(2026, 11, 2, 0, 0));
        assertThat(ConsumptionRange.WEEK.end(start)).isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
        assertThat(ConsumptionRange.WEEK.previous(start)).isEqualTo(LocalDateTime.of(2026, 10, 26, 0, 0));
        assertThat(ConsumptionRange.WEEK.getUnit()).isEqualTo("day");
        assertThat(ConsumptionRange.WEEK.start(LocalDate.of(2026, 11, 8))).isEqualTo(start);
        assertThat(ConsumptionRange.WEEK.start(LocalDate.of(2026, 11, 2))).isEqualTo(start);
    }

    //the month before, not 30 days: March before April, February of 28 days before March
    @Test
    void should_make_a_month_of_days() {
        final LocalDateTime start = ConsumptionRange.MONTH.start(LocalDate.of(2026, 3, 31));

        assertThat(start).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(ConsumptionRange.MONTH.end(start)).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
        assertThat(ConsumptionRange.MONTH.previous(start)).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        assertThat(ConsumptionRange.MONTH.getUnit()).isEqualTo("day");
    }

    @Test
    void should_make_a_year_of_months() {
        final LocalDateTime start = ConsumptionRange.YEAR.start(AT);

        assertThat(start).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(ConsumptionRange.YEAR.end(start)).isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0));
        assertThat(ConsumptionRange.YEAR.previous(start)).isEqualTo(LocalDateTime.of(2025, 1, 1, 0, 0));
        assertThat(ConsumptionRange.YEAR.getUnit()).isEqualTo("month");
    }

    @Test
    void should_read_the_names_of_the_api() {
        assertThat(ConsumptionRange.of("day")).isEqualTo(ConsumptionRange.DAY);
        assertThat(ConsumptionRange.of("week")).isEqualTo(ConsumptionRange.WEEK);
        assertThat(ConsumptionRange.of("month")).isEqualTo(ConsumptionRange.MONTH);
        assertThat(ConsumptionRange.of("year")).isEqualTo(ConsumptionRange.YEAR);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "DAY", "hour", "days", " day"})
    void should_refuse_any_other_range(final String value) {
        assertThatThrownBy(() -> ConsumptionRange.of(value))
            .isInstanceOfSatisfying(ValveException.class,
                exception -> assertThat(exception.getError()).isEqualTo(WaterError.INVALID_RANGE));
    }
}
