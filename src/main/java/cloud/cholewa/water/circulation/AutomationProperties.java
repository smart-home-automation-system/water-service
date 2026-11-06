package cloud.cholewa.water.circulation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

//The automation of the circulation pump (HAS-405); temperatures in degrees C, a bare duration is minutes - without the
//unit it would bind as milliseconds. Pinned by AutomationPropertiesTest.
//enabled - the automation runs (true); false leaves the manual start alone, without a release
//rooms - the rooms of light-service whose motion and lights start a run (the two bathrooms; 1 - 10)
//startBelow - an automatic run only while the return is below it (30; 15 - 60) - a warm loop needs no run
//pauseAfter - no automatic run within it after the end of a run stopped by the temperature or the limit (PT20M; 0 - 2 h)
//quietFrom, quietTo - no automatic run between them; across midnight when from is after to (23:30 - 05:30); equal ends
//  are refused - they would be the whole day
//comfortWindows - a run every minute of a window while the conditions hold (06:00 - 08:00, 19:00 - 23:30; at most 6;
//  equal ends refused)
//minRise, noRiseRuns - an alert when noRiseRuns runs in a row raised the return by less than minRise (1.5; 0.5 - 10 and
//  3; 2 - 10), a pump or a sensor to look at (owner 2026-11-06: by the rise, not by the limit - the return rarely reaches
//  its target within a run, the tank being about 43 C)
@Validated
@ConfigurationProperties(prefix = "water.circulation.automation")
public record AutomationProperties(
    @NotNull Boolean enabled,
    @NotNull @Size(min = 1, max = 10) List<@NotBlank String> rooms,
    @NotNull @DecimalMin("15") @DecimalMax("60") Double startBelow,
    @NotNull @DurationUnit(ChronoUnit.MINUTES) @DurationMin(minutes = 0) @DurationMax(hours = 2) Duration pauseAfter,
    @NotNull LocalTime quietFrom,
    @NotNull LocalTime quietTo,
    @NotNull @Size(max = 6) List<@Valid @NotNull Window> comfortWindows,
    @NotNull @DecimalMin("0.5") @DecimalMax("10") Double minRise,
    @NotNull @Min(2) @Max(10) Integer noRiseRuns
) {

    //a time of the day from (included) to (not); across midnight when from is after to
    public record Window(@NotNull LocalTime from, @NotNull LocalTime to) {

        @AssertTrue(message = "a window has two different ends")
        boolean isTwoEnds() {
            return from == null || !from.equals(to);
        }

        boolean contains(final LocalTime time) {
            return from.isBefore(to)
                ? !time.isBefore(from) && time.isBefore(to)
                : !time.isBefore(from) || time.isBefore(to);
        }

        String label() {
            return from + "-" + to;
        }
    }

    @AssertTrue(message = "the quiet hours have two different ends")
    boolean isQuietTwoEnds() {
        return quietFrom == null || !quietFrom.equals(quietTo);
    }

    boolean quiet(final LocalTime time) {
        return new Window(quietFrom, quietTo).contains(time);
    }
}
