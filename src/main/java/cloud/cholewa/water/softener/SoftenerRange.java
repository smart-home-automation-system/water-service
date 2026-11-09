package cloud.cholewa.water.softener;

import cloud.cholewa.water.infrastructure.error.ValveException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

//The ranges the history, the days and the regenerations of the softener are asked for (HAS-398). The rules of the
//temperature history of HistoryRange - the years and the order of the bounds - with two differences: at most a year, so
//that the salt and the treated water can be drawn over months, and a bucket of a day beyond a month, 366 points at most.
//A range that breaks a rule is INVALID_RANGE with its reason, and as the parameters are read as text, a missing or a wrong
//one has the code too. Pinned by SoftenerRangeTest
public final class SoftenerRange {

    static final long MAX_DAYS = 366;
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 9999;

    //A row is stored at most once a read (every 5 minutes) and only on a change. Each width divides a day, which is what
    //makes a bucket start on the clock of the house
    private static final Duration SHORT_RANGE = Duration.ofDays(2);
    private static final Duration SHORT_RANGE_BUCKET = Duration.ofMinutes(5);
    private static final Duration MEDIUM_RANGE = Duration.ofDays(8);
    private static final Duration MEDIUM_RANGE_BUCKET = Duration.ofMinutes(30);
    private static final Duration LONG_RANGE = Duration.ofDays(31);
    private static final Duration LONG_RANGE_BUCKET = Duration.ofHours(2);
    private static final Duration YEAR_RANGE_BUCKET = Duration.ofDays(1);

    private SoftenerRange() {
    }

    //a date-time of the history, YYYY-MM-DDTHH:MM[:SS]
    public static LocalDateTime dateTime(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw ValveException.invalidRange(name + " is required");
        }
        try {
            return checked(name, LocalDateTime.parse(value.trim()));
        } catch (final DateTimeParseException notDateTime) {
            throw ValveException.invalidRange(name + " must be YYYY-MM-DDTHH:MM:SS, not '" + value + "'");
        }
    }

    //a day of the days and the regenerations, YYYY-MM-DD; none given is the default
    public static LocalDate day(final String name, final String value, final LocalDate otherwise) {
        if (value == null || value.isBlank()) {
            return otherwise;
        }
        try {
            final LocalDate day = LocalDate.parse(value.trim());
            checked(name, day.atStartOfDay());
            return day;
        } catch (final DateTimeParseException notDay) {
            throw ValveException.invalidRange(name + " must be YYYY-MM-DD, not '" + value + "'");
        }
    }

    //from before to, both within the years, at most MAX_DAYS calendar days apart (the time of day deciding a range of
    //exactly the limit) - not from.plusDays: a bound at the edge of what LocalDateTime holds would throw
    public static void check(final LocalDateTime from, final LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw ValveException.invalidRange("from must be before to");
        }
        final long days = to.toLocalDate().toEpochDay() - from.toLocalDate().toEpochDay();
        if (days > MAX_DAYS || (days == MAX_DAYS && to.toLocalTime().isAfter(from.toLocalTime()))) {
            throw ValveException.invalidRange("The range must not be longer than " + MAX_DAYS + " days");
        }
    }

    //The width of the buckets of a valid range, counted on the wall clock (a fixed offset, not the zone of the house: two
    //days across a clock change are two days here)
    public static Duration bucket(final LocalDateTime from, final LocalDateTime to) {
        final Duration length = Duration.between(from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC));
        if (length.compareTo(SHORT_RANGE) <= 0) {
            return SHORT_RANGE_BUCKET;
        }
        if (length.compareTo(MEDIUM_RANGE) <= 0) {
            return MEDIUM_RANGE_BUCKET;
        }
        return length.compareTo(LONG_RANGE) <= 0 ? LONG_RANGE_BUCKET : YEAR_RANGE_BUCKET;
    }

    private static LocalDateTime checked(final String name, final LocalDateTime value) {
        if (value.getYear() < MIN_YEAR || value.getYear() > MAX_YEAR) {
            throw ValveException.invalidRange(name + " must be within the years " + MIN_YEAR + " to " + MAX_YEAR);
        }
        return value;
    }
}
