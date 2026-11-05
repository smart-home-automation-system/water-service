package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.model.BillRequest;
import cloud.cholewa.water.model.ImportedDay;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

//What a bill and an import of the daily consumption must be (HAS-318), checked before anything is written. Every refusal
//is INVALID_BILL / INVALID_IMPORT with the reason as details - English, written here, never a text of a JVM locale. The
//overlap of two bills needs the table and is checked by BillService. A copy of these rules belongs to the dashboard
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BillRules {

    static final int FIRST_YEAR = 2000;
    static final int NOTE_LENGTH = 500;
    static final int MAX_IMPORTED_DAYS = 3660;
    //NUMERIC(12, 3) and NUMERIC(12, 2) of V4
    private static final BigDecimal VOLUME_LIMIT = new BigDecimal("1000000000");
    private static final BigDecimal AMOUNT_LIMIT = new BigDecimal("10000000000");

    public static void checkBill(final BillRequest bill, final LocalDate today) {
        require(bill != null, "The bill is required");
        require(bill.periodFrom() != null, "periodFrom is required");
        require(bill.periodTo() != null, "periodTo is required");
        require(bill.billedVolume() != null, "billedVolume is required");
        require(!bill.periodTo().isBefore(bill.periodFrom()), "periodTo must not be before periodFrom");
        require(bill.periodFrom().getYear() >= FIRST_YEAR, "periodFrom must not be before " + FIRST_YEAR);
        require(!bill.periodTo().isAfter(today), "periodTo must not be in the future");
        checkVolume(bill.billedVolume(), "billedVolume", BillRules::refuseBill);
        if (bill.meterReading() != null) {
            checkVolume(bill.meterReading(), "meterReading", BillRules::refuseBill);
        }
        if (bill.amount() != null) {
            require(bill.amount().signum() >= 0, "amount must not be negative");
            require(bill.amount().stripTrailingZeros().scale() <= 2, "amount has at most 2 decimals");
            require(bill.amount().compareTo(AMOUNT_LIMIT) < 0, "amount is too large");
        }
        require(bill.note() == null || bill.note().length() <= NOTE_LENGTH,
            "note has at most " + NOTE_LENGTH + " characters");
    }

    //last: the first day of the readings of the house - an imported day must not be later; on that first day, watched
    //only from its first row, the imported value wins over the measured part (WaterMeterRepository.CONSUMPTION)
    public static void checkImport(final List<ImportedDay> days, final LocalDate last) {
        if (days == null) {
            throw ValveException.invalidImport("The days are required");
        }
        if (days.size() > MAX_IMPORTED_DAYS) {
            throw ValveException.invalidImport("At most " + MAX_IMPORTED_DAYS + " days");
        }
        final Set<LocalDate> seen = new HashSet<>();
        for (final ImportedDay day : days) {
            if (day == null || day.day() == null || day.volume() == null) {
                throw ValveException.invalidImport("Every day needs its day and volume");
            }
            if (!seen.add(day.day())) {
                throw ValveException.invalidImport("The day " + day.day() + " is sent twice");
            }
            if (day.day().getYear() < FIRST_YEAR) {
                throw ValveException.invalidImport("The day " + day.day() + " is before " + FIRST_YEAR);
            }
            if (day.day().isAfter(last)) {
                throw ValveException.invalidImport("The day " + day.day() + " is after " + last
                    + ", the first day of the readings of the house");
            }
            checkVolume(day.volume(), "The volume of " + day.day(), ValveException::invalidImport);
        }
    }

    private static void checkVolume(final BigDecimal volume, final String name,
                                    final Function<String, ValveException> refusal) {
        if (volume.signum() < 0) {
            throw refusal.apply(name + " must not be negative");
        }
        if (volume.stripTrailingZeros().scale() > 3) {
            throw refusal.apply(name + " has at most 3 decimals");
        }
        if (volume.compareTo(VOLUME_LIMIT) >= 0) {
            throw refusal.apply(name + " is too large");
        }
    }

    private static void require(final boolean condition, final String reason) {
        if (!condition) {
            throw refuseBill(reason);
        }
    }

    private static ValveException refuseBill(final String reason) {
        return ValveException.invalidBill(Objects.requireNonNull(reason));
    }
}
