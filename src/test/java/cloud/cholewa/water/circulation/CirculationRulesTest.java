package cloud.cholewa.water.circulation;

import cloud.cholewa.water.circulation.CirculationRules.Stop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//When a run of the circulation pump ends (HAS-404): the return, the limit, the relay found off, a silent Uni
class CirculationRulesTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 5, 19, 0);
    private static final CirculationRun RUN = new CirculationRun(1L, START, "manual", "dashboard", 24.0, true);
    private static final CirculationProperties PROPERTIES = new CirculationProperties(35.0, Duration.ofMinutes(3), 38.0,
        true);

    private static UniReading read(final Boolean on, final Double returnTemperature, final int secondsAfter) {
        return new UniReading(on, 45.0, returnTemperature, START.plusSeconds(secondsAfter));
    }

    @Test
    void should_go_on_while_the_return_is_cold_and_the_limit_far() {
        assertThat(CirculationRules.afterRead(RUN, read(true, 30.0, 60), PROPERTIES)).isEmpty();
    }

    //at the target, not only above it
    @ParameterizedTest
    @CsvSource({"35.0", "36.4"})
    void should_stop_once_the_return_reaches_its_target(final double returnTemperature) {
        assertThat(CirculationRules.afterRead(RUN, read(true, returnTemperature, 50), PROPERTIES))
            .contains(new Stop("temperature", true));
    }

    @Test
    void should_stop_at_the_limit_whatever_the_return() {
        assertThat(CirculationRules.afterRead(RUN, read(true, 30.0, 180), PROPERTIES)).contains(new Stop("limit", true));
        assertThat(CirculationRules.afterRead(RUN, read(true, null, 181), PROPERTIES)).contains(new Stop("limit", true));
    }

    //a sensor that is not on the return (decided with the owner): only the limit stops a run
    @Test
    void should_leave_the_return_alone_without_stop_on_return() {
        final CirculationProperties limitOnly = new CirculationProperties(35.0, Duration.ofMinutes(3), 38.0, false);

        assertThat(CirculationRules.afterRead(RUN, read(true, 40.0, 60), limitOnly)).isEmpty();
        assertThat(CirculationRules.afterRead(RUN, read(true, 40.0, 180), limitOnly)).contains(new Stop("limit", true));
    }

    //the relay off: the timer of the device near max-run, otherwise something else switched it off - nothing to send
    @Test
    void should_end_a_run_whose_relay_is_found_off() {
        assertThat(CirculationRules.afterRead(RUN, read(false, 33.0, 175), PROPERTIES)).contains(new Stop("limit", false));
        assertThat(CirculationRules.afterRead(RUN, read(false, 33.0, 169), PROPERTIES)).contains(new Stop("device", false));
    }

    //a start the Uni did not answer, found off: it may never have reached the pump - a failure, not the device
    @Test
    void should_end_an_unanswered_start_found_off_as_failure() {
        final CirculationRun unanswered = RUN.unanswered();

        assertThat(CirculationRules.afterRead(unanswered, read(false, 24.0, 5), PROPERTIES))
            .contains(new Stop("failure", false));
        assertThat(CirculationRules.afterRead(unanswered, read(true, 30.0, 5), PROPERTIES)).isEmpty();
    }

    //the end of a run given up as unheard is the end of its timer at the latest
    @Test
    void should_end_an_unheard_run_at_its_timer() {
        assertThat(CirculationRules.unheardEnd(RUN, START.plusSeconds(240), PROPERTIES)).isEqualTo(START.plusSeconds(180));
        assertThat(CirculationRules.unheardEnd(RUN, START.plusSeconds(100), PROPERTIES)).isEqualTo(START.plusSeconds(100));
    }

    //the read that saw the pump off a moment before the start says nothing of the run
    @Test
    void should_ignore_a_read_asked_before_the_start() {
        assertThat(CirculationRules.afterRead(RUN, read(false, 22.0, -1), PROPERTIES)).isEmpty();
    }

    //an answer without the relay is no answer about the pump: the run waits, then is given up like an unheard one
    @Test
    void should_give_up_a_run_whose_relay_is_not_known_past_the_limit() {
        assertThat(CirculationRules.afterRead(RUN, read(null, 30.0, 200), PROPERTIES)).isEmpty();
        assertThat(CirculationRules.afterRead(RUN, read(null, 30.0, 240), PROPERTIES)).contains(new Stop("failure", false));
    }

    @Test
    void should_give_up_a_run_of_a_silent_uni_a_minute_after_the_limit() {
        assertThat(CirculationRules.unheard(RUN, START.plusSeconds(239), PROPERTIES)).isEmpty();
        assertThat(CirculationRules.unheard(RUN, START.plusSeconds(240), PROPERTIES)).contains(new Stop("failure", false));
    }
}
