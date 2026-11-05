package cloud.cholewa.water.command;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The confirmation of a command of the valve (HAS-321, the rule of HAS-250): the report of the valve in the asked state
//within 60 s confirms it, nothing within them leaves it unconfirmed, the third in a row is the alert of a run
class CommandWatchTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 5, 18, 0);
    private static final ValveCommand CLOSE = new ValveCommand(7L, false, "dashboard", T0);

    @Test
    void should_know_nothing_before_a_command() {
        assertThat(CommandWatch.NONE.lastConfirmed()).isNull();
        assertThat(CommandWatch.NONE.reported(false, T0)).isEmpty();
    }

    //false from the sending until the report arrives
    @Test
    void should_be_unconfirmed_while_the_command_waits() {
        assertThat(CommandWatch.NONE.issued(CLOSE).lastConfirmed()).isFalse();
    }

    @Test
    void should_confirm_by_the_report_of_the_asked_state_within_the_window() {
        final CommandWatch waiting = new CommandWatch(null, false, 2, false).issued(CLOSE);

        assertThat(waiting.reported(true, T0.plusSeconds(5))).isEmpty();
        assertThat(waiting.reported(false, T0.minusNanos(1000))).isEmpty();
        assertThat(waiting.reported(false, T0.plusSeconds(61))).isEmpty();
        assertThat(waiting.reported(false, T0.plusSeconds(60)))
            .contains(new CommandWatch(null, true, 0, false));
    }

    //nothing within the window: unconfirmed, counted in a row; one confirmed or replaced is not
    @Test
    void should_count_the_unconfirmed_commands_in_a_row() {
        final CommandWatch waiting = new CommandWatch(null, false, 2, false).issued(CLOSE);

        assertThat(waiting.expired(CLOSE)).contains(new CommandWatch(null, false, 3, false));
        assertThat(waiting.reported(false, T0.plusSeconds(1)).orElseThrow().expired(CLOSE)).isEmpty();
        assertThat(waiting.issued(new ValveCommand(8L, true, "dashboard", T0.plusSeconds(10))).expired(CLOSE)).isEmpty();
    }

    //an alert once a run: announced holds until a command is confirmed
    @Test
    void should_announce_a_run_once() {
        final CommandWatch announced = new CommandWatch(null, false, 3, false).withAnnounced();

        assertThat(announced.issued(CLOSE).expired(CLOSE).orElseThrow()).isEqualTo(new CommandWatch(null, false, 4, true));
        assertThat(announced.issued(CLOSE).reported(false, T0).orElseThrow().announced()).isFalse();
    }
}
