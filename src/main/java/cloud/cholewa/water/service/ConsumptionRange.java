package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.UnaryOperator;

//The ranges of the consumption of water (HAS-317): a day in hours, a week (Monday to Sunday) and a month in days, a year
//in months. A range starts at midnight of the house on its first day; the one before it is the same range one period
//earlier (the month before, not 30 days). The unit is the field of date_trunc of PostgreSQL its buckets are cut by
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum ConsumptionRange {

    DAY(UnaryOperator.identity(), ChronoUnit.DAYS, "hour"),
    WEEK(day -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), ChronoUnit.WEEKS, "day"),
    MONTH(day -> day.withDayOfMonth(1), ChronoUnit.MONTHS, "day"),
    YEAR(day -> day.withDayOfYear(1), ChronoUnit.YEARS, "month");

    private final UnaryOperator<LocalDate> firstDay;
    private final ChronoUnit period;
    private final String unit;

    //the name of the range in the API, lower case
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public LocalDateTime start(final LocalDate at) {
        return firstDay.apply(at).atStartOfDay();
    }

    public LocalDateTime end(final LocalDateTime start) {
        return start.plus(1, period);
    }

    public LocalDateTime previous(final LocalDateTime start) {
        return start.minus(1, period);
    }

    //day, week, month or year exactly - anything else, a missing range included, is INVALID_RANGE
    public static ConsumptionRange of(final String value) {
        return Arrays.stream(values())
            .filter(range -> range.value().equals(value))
            .findFirst()
            .orElseThrow(() -> ValveException.invalidRange(
                "The range must be day, week, month or year, not '%s'".formatted(value)));
    }
}
