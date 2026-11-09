package cloud.cholewa.water.softener;

import cloud.cholewa.water.infrastructure.error.ValveException;
import cloud.cholewa.water.infrastructure.error.WaterError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//The ranges of the softener (HAS-398): at most a year, buckets of 5 min / 30 min / 2 h / a day by the length, every refusal
//INVALID_RANGE with its reason
class SoftenerRangeTest {

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 1, 1, 0, 0);

    @ParameterizedTest
    @CsvSource({"P2D,PT5M", "P3D,PT30M", "P8D,PT30M", "P9D,PT2H", "P31D,PT2H", "P32D,P1D", "P366D,P1D"})
    void should_choose_the_bucket_by_the_length(final Duration length, final Duration bucket) {
        assertThat(SoftenerRange.bucket(FROM, FROM.plus(length))).isEqualTo(bucket);
    }

    @Test
    void should_take_a_year_and_refuse_a_day_more() {
        SoftenerRange.check(FROM, FROM.plusDays(366));

        assertRefused(() -> SoftenerRange.check(FROM, FROM.plusDays(366).plusMinutes(1)),
            "The range must not be longer than 366 days");
        assertRefused(() -> SoftenerRange.check(FROM, FROM), "from must be before to");
    }

    @Test
    void should_read_the_parameters_as_text() {
        assertThat(SoftenerRange.dateTime("from", "2026-11-09T10:00")).isEqualTo(LocalDateTime.of(2026, 11, 9, 10, 0));
        assertThat(SoftenerRange.day("to", null, LocalDate.of(2026, 11, 9))).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(SoftenerRange.day("to", "2026-11-01", LocalDate.of(2026, 11, 9))).isEqualTo(LocalDate.of(2026, 11, 1));

        assertRefused(() -> SoftenerRange.dateTime("from", null), "from is required");
        assertRefused(() -> SoftenerRange.dateTime("from", "yesterday"), "from must be YYYY-MM-DDTHH:MM:SS, not 'yesterday'");
        assertRefused(() -> SoftenerRange.dateTime("to", "1999-12-31T00:00"), "to must be within the years 2000 to 9999");
        assertRefused(() -> SoftenerRange.day("from", "2026-13-01", null), "from must be YYYY-MM-DD, not '2026-13-01'");
    }

    private static void assertRefused(final Runnable call, final String reason) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(ValveException.class, refused -> {
                assertThat(refused.getError()).isEqualTo(WaterError.INVALID_RANGE);
                assertThat(refused.getMessage()).isEqualTo(reason);
            });
    }
}
