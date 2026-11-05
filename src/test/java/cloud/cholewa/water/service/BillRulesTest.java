package cloud.cholewa.water.service;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import cloud.cholewa.water.model.BillRequest;
import cloud.cholewa.water.model.ImportedDay;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//What a bill and an import must be (HAS-318): every refusal with its code and an English reason
class BillRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    private static final BillRequest BILL = new BillRequest(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 20),
        new BigDecimal("11"), new BigDecimal("1234.5"), new BigDecimal("98.76"), "Waterworks");

    @Test
    void should_take_a_bill_and_its_optional_fields_left_out() {
        assertThatCode(() -> BillRules.checkBill(BILL, TODAY)).doesNotThrowAnyException();
        assertThatCode(() -> BillRules.checkBill(new BillRequest(TODAY, TODAY, BigDecimal.ZERO, null, null, null), TODAY))
            .doesNotThrowAnyException();
    }

    @Test
    void should_refuse_a_bill_that_is_not_one() {
        refusedBill(() -> BillRules.checkBill(null, TODAY), "The bill is required");
        refusedBill(() -> BillRules.checkBill(bill(null, TODAY, "1"), TODAY), "periodFrom is required");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, null, "1"), TODAY), "periodTo is required");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, TODAY, null), TODAY), "billedVolume is required");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, TODAY.minusDays(1), "1"), TODAY),
            "periodTo must not be before periodFrom");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, TODAY.plusDays(1), "1"), TODAY),
            "periodTo must not be in the future");
        refusedBill(() -> BillRules.checkBill(bill(LocalDate.of(1999, 12, 31), TODAY, "1"), TODAY),
            "periodFrom must not be before 2000");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, TODAY, "-0.001"), TODAY), "billedVolume must not be negative");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, TODAY, "1.0001"), TODAY), "billedVolume has at most 3 decimals");
        refusedBill(() -> BillRules.checkBill(bill(TODAY, TODAY, "1000000000"), TODAY), "billedVolume is too large");
        refusedBill(() -> BillRules.checkBill(new BillRequest(TODAY, TODAY, BigDecimal.ONE, new BigDecimal("-1"), null,
            null), TODAY), "meterReading must not be negative");
        refusedBill(() -> BillRules.checkBill(new BillRequest(TODAY, TODAY, BigDecimal.ONE, null, new BigDecimal("-1"),
            null), TODAY), "amount must not be negative");
        refusedBill(() -> BillRules.checkBill(new BillRequest(TODAY, TODAY, BigDecimal.ONE, null, new BigDecimal("1.001"),
            null), TODAY), "amount has at most 2 decimals");
        refusedBill(() -> BillRules.checkBill(new BillRequest(TODAY, TODAY, BigDecimal.ONE, null, null, "x".repeat(501)),
            TODAY), "note has at most 500 characters");
    }

    //trailing zeros are no decimals: 11.000 is 11
    @Test
    void should_read_trailing_zeros_as_no_decimals() {
        assertThatCode(() -> BillRules.checkBill(bill(TODAY, TODAY, "11.00000"), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void should_take_days_before_the_first_reading() {
        assertThatCode(() -> BillRules.checkImport(List.of(day("2026-02-01", "0.4"), day("2026-11-05", "0")), TODAY))
            .doesNotThrowAnyException();
        assertThatCode(() -> BillRules.checkImport(List.of(), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void should_refuse_an_import_that_is_not_one() {
        refusedImport(() -> BillRules.checkImport(null, TODAY), "The days are required");
        refusedImport(() -> BillRules.checkImport(List.of(day("2026-11-06", "1")), TODAY),
            "The day 2026-11-06 is after 2026-11-05, the first day of the readings of the house");
        refusedImport(() -> BillRules.checkImport(List.of(day("2026-10-01", "1"), day("2026-10-01", "2")), TODAY),
            "The day 2026-10-01 is sent twice");
        refusedImport(() -> BillRules.checkImport(List.of(day("1999-12-31", "1")), TODAY), "The day 1999-12-31 is before 2000");
        refusedImport(() -> BillRules.checkImport(List.of(day("2026-10-01", "-1")), TODAY),
            "The volume of 2026-10-01 must not be negative");
        refusedImport(() -> BillRules.checkImport(List.of(day("2026-10-01", "0.0001")), TODAY),
            "The volume of 2026-10-01 has at most 3 decimals");
        refusedImport(() -> BillRules.checkImport(Collections.singletonList(null), TODAY),
            "Every day needs its day and volume");
        refusedImport(() -> BillRules.checkImport(List.of(new ImportedDay(LocalDate.of(2026, 10, 1), null)), TODAY),
            "Every day needs its day and volume");

        final List<ImportedDay> tooMany = new ArrayList<>();
        for (int index = 0; index <= BillRules.MAX_IMPORTED_DAYS; index++) {
            tooMany.add(new ImportedDay(LocalDate.of(2010, 1, 1).plusDays(index), BigDecimal.ONE));
        }
        refusedImport(() -> BillRules.checkImport(tooMany, TODAY), "At most 3660 days");
    }

    private static BillRequest bill(final LocalDate from, final LocalDate to, final String volume) {
        return new BillRequest(from, to, volume == null ? null : new BigDecimal(volume), null, null, null);
    }

    private static ImportedDay day(final String day, final String volume) {
        return new ImportedDay(LocalDate.parse(day), new BigDecimal(volume));
    }

    private static void refusedBill(final ThrowableAssert.ThrowingCallable call, final String reason) {
        refused(call, WaterError.INVALID_BILL, reason);
    }

    private static void refusedImport(final ThrowableAssert.ThrowingCallable call, final String reason) {
        refused(call, WaterError.INVALID_IMPORT, reason);
    }

    private static void refused(final ThrowableAssert.ThrowingCallable call, final WaterError error, final String reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ValveException.class, exception -> {
            assertThat(exception.getError()).isEqualTo(error);
            assertThat(exception.getMessage()).isEqualTo(reason);
        });
    }
}
