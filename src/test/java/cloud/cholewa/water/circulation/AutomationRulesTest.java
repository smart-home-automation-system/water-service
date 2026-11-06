package cloud.cholewa.water.circulation;

import cloud.cholewa.water.circulation.AutomationProperties.Window;
import cloud.cholewa.water.presence.HouseOccupancy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

//When an automatic run may start (HAS-405): the quiet hours, a run going on, the pump on, the Uni not read, a cold tank,
//a warm return, the pause, the house - unknown never starts
class AutomationRulesTest {

    static final AutomationProperties AUTOMATION = new AutomationProperties(true, List.of("bathroom up", "bathroom down"),
        30.0, Duration.ofMinutes(20), LocalTime.of(23, 30), LocalTime.of(5, 30),
        List.of(new Window(LocalTime.of(6, 0), LocalTime.of(8, 0)), new Window(LocalTime.of(19, 0), LocalTime.of(23, 30))),
        1.5, 3);
    static final CirculationProperties CIRCULATION = new CirculationProperties(35.0, Duration.ofMinutes(3), 38.0, true);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 19, 0);

    private static Optional<String> blocked(final LocalDateTime now, final UniReading reading, final boolean runGoing) {
        return AutomationRules.blockedNow(now, Optional.ofNullable(reading), runGoing, AUTOMATION, CIRCULATION);
    }

    private static UniReading read(final Boolean pumpOn, final Double tank, final Double returnTemperature) {
        return new UniReading(pumpOn, tank, returnTemperature, NOW.minusSeconds(10));
    }

    @Test
    void should_allow_a_run_with_a_warm_tank_and_a_cold_return() {
        assertThat(blocked(NOW, read(false, 43.0, 26.4), false)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"23:30", "23:59", "00:00", "03:00", "05:29"})
    void should_not_start_in_the_quiet_hours(final LocalTime time) {
        final LocalDateTime night = NOW.with(time);
        final UniReading reading = new UniReading(false, 43.0, 26.4, night.minusSeconds(10));

        assertThat(AutomationRules.blockedNow(night, Optional.of(reading), false, AUTOMATION, CIRCULATION))
            .contains("the quiet hours");
    }

    @ParameterizedTest
    @CsvSource({"05:30", "06:00", "12:00", "23:29"})
    void should_start_outside_the_quiet_hours(final LocalTime time) {
        final LocalDateTime day = NOW.with(time);
        final UniReading reading = new UniReading(false, 43.0, 26.4, day.minusSeconds(10));

        assertThat(AutomationRules.blockedNow(day, Optional.of(reading), false, AUTOMATION, CIRCULATION)).isEmpty();
    }

    @Test
    void should_not_start_while_a_run_goes_on_or_the_pump_is_on() {
        assertThat(blocked(NOW, read(false, 43.0, 26.4), true)).contains("a run goes on");
        assertThat(blocked(NOW, read(true, 43.0, 26.4), false)).contains("the pump is on");
    }

    //unknown never starts: no read, a read older than a minute
    @Test
    void should_not_start_without_a_fresh_read_of_the_uni() {
        assertThat(blocked(NOW, null, false)).contains("the Uni was not read for a minute");
        assertThat(blocked(NOW, new UniReading(false, 43.0, 26.4, NOW.minusSeconds(61)), false))
            .contains("the Uni was not read for a minute");
    }

    @Test
    void should_not_start_with_a_cold_tank_or_one_not_reported() {
        assertThat(blocked(NOW, read(false, 37.9, 26.4), false)).contains("the tank at 37.9 C");
        assertThat(blocked(NOW, read(false, null, 26.4), false)).contains("the tank not reported");
        assertThat(blocked(NOW, read(false, 38.0, 26.4), false)).isEmpty();
    }

    @Test
    void should_not_start_with_a_warm_return_or_one_not_reported() {
        assertThat(blocked(NOW, read(false, 43.0, 30.0), false)).contains("the return at 30.0 C");
        assertThat(blocked(NOW, read(false, 43.0, null), false)).contains("the return not reported");
        assertThat(blocked(NOW, read(false, 43.0, 29.9), false)).isEmpty();
    }

    @Test
    void should_pause_after_a_run_that_ended_by_the_temperature_or_the_limit() {
        assertThat(AutomationRules.paused(NOW, NOW.minusMinutes(19), Duration.ofMinutes(20)))
            .contains("the pause after the run that ended at 18:41");
        assertThat(AutomationRules.paused(NOW, NOW.minusMinutes(20), Duration.ofMinutes(20))).isEmpty();
        assertThat(AutomationRules.paused(NOW, null, Duration.ofMinutes(20))).isEmpty();
    }

    @Test
    void should_start_only_while_somebody_is_at_home() {
        assertThat(AutomationRules.nobodyHome(HouseOccupancy.OCCUPIED)).isEmpty();
        assertThat(AutomationRules.nobodyHome(HouseOccupancy.EMPTY)).contains("the house is empty");
        assertThat(AutomationRules.nobodyHome(HouseOccupancy.UNKNOWN)).contains("the house is unknown");
    }

    @ParameterizedTest
    @CsvSource({"05:59,false", "06:00,true", "07:59,true", "08:00,false", "19:00,true", "23:29,true", "23:30,false"})
    void should_know_the_comfort_windows(final LocalTime time, final boolean inside) {
        assertThat(AUTOMATION.comfortWindows().stream().anyMatch(window -> window.contains(time))).isEqualTo(inside);
    }
}
