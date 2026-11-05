package cloud.cholewa.water.circulation;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//What is known of the pump (HAS-404): the newest read wins, on since the first read of it on, a run ended once
class CirculationPumpTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 5, 19, 0);

    private final CirculationPump pump = new CirculationPump();

    //two reads answering out of order: the one asked later stays
    @Test
    void should_keep_the_newest_read() {
        pump.read(new UniReading(true, 45.0, 30.0, AT.plusSeconds(5)));
        pump.read(new UniReading(false, 45.0, 24.0, AT));

        assertThat(pump.reading()).contains(new UniReading(true, 45.0, 30.0, AT.plusSeconds(5)));
        assertThat(pump.onSince()).contains(AT.plusSeconds(5));
    }

    @Test
    void should_be_on_since_the_start_of_a_run_and_off_once_read_off() {
        final CirculationRun run = new CirculationRun(null, AT, "manual", "dashboard", 24.0, true);
        pump.started(run);
        pump.read(new UniReading(true, 45.0, 26.0, AT.plusSeconds(5)));
        assertThat(pump.onSince()).contains(AT);

        pump.read(new UniReading(false, 45.0, 34.0, AT.plusSeconds(60)));
        assertThat(pump.onSince()).isEmpty();
    }

    @Test
    void should_end_a_run_once_and_keep_the_id_of_its_row() {
        final CirculationRun run = new CirculationRun(null, AT, "manual", "dashboard", 24.0, true);
        pump.started(run);
        pump.stored(run.withId(9L));

        assertThat(pump.run()).contains(run.withId(9L));
        assertThat(pump.isCurrent(run)).isTrue();
        assertThat(pump.ended(run)).isTrue();
        assertThat(pump.ended(run)).isFalse();
        assertThat(pump.isCurrent(run)).isFalse();

        pump.stored(run.withId(10L));
        assertThat(pump.run()).isEmpty();
    }
}
