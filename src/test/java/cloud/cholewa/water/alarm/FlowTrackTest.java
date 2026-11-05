package cloud.cholewa.water.alarm;

import cloud.cholewa.water.valve.ValveProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

//The runs of the flow alarms (HAS-320) from the reports of the flow rate as they arrive: a pause no longer than the
//pause of the alarms is the same flow, a longer one starts a new run, a silence of the valve is never a flow
class FlowTrackTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final Duration PAUSE = Duration.ofMinutes(1);
    private static final FlowProperties FLOW = new FlowProperties(25.0, Duration.ofMinutes(3), PAUSE,
        Duration.ofMinutes(30), 0.5, Duration.ofMinutes(2), false);

    private final FlowTrack sut = new FlowTrack(FLOW, new ValveProperties(Duration.ofMinutes(5)));

    @Test
    void should_know_no_run_before_a_flow() {
        sut.flow(0, T0);

        assertThat(sut.current().runs()).isEmpty();
        assertThat(sut.current().litres()).isZero();
    }

    //each run by its own bound: 4 l/min is above zero and above the noise, not a high flow
    @Test
    void should_start_a_run_above_each_bound() {
        sut.flow(4, T0);

        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW)).isEqualTo(new FlowRun(T0, T0, true, 4, null));
        assertThat(sut.current().run(Alarm.EMPTY_HOUSE_FLOW)).isEqualTo(new FlowRun(T0, T0, true, 4, null));
        assertThat(sut.current().run(Alarm.HIGH_FLOW)).isNull();

        sut.flow(30, T0.plusMinutes(1));

        assertThat(sut.current().run(Alarm.HIGH_FLOW)).isEqualTo(new FlowRun(T0.plusMinutes(1), T0.plusMinutes(1), true,
            30, null));
        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW)).isEqualTo(new FlowRun(T0, T0.plusMinutes(1), true, 30, null));
    }

    //a stop of 50 s is the same flow - the minute pass alone would not see it
    @Test
    void should_keep_one_run_across_a_pause_no_longer_than_the_pause() {
        sut.flow(4, T0);
        sut.flow(0, T0.plusMinutes(4));
        sut.flow(3, T0.plusMinutes(4).plusSeconds(50));

        final FlowRun run = sut.current().run(Alarm.CONTINUOUS_FLOW);
        assertThat(run.start()).isEqualTo(T0);
        assertThat(run.above()).isTrue();
        assertThat(run.lastedAtLeast(Duration.ofMinutes(30), T0.plusMinutes(30))).isTrue();
    }

    //a stop of more than the pause ends the run; the next flow starts a new one and knows where the last ended
    @Test
    void should_start_a_new_run_after_a_longer_pause() {
        sut.flow(4, T0);
        sut.flow(0, T0.plusMinutes(4));

        final FlowRun fallen = sut.current().run(Alarm.CONTINUOUS_FLOW);
        assertThat(fallen.activeAt(T0.plusMinutes(5), PAUSE)).isTrue();
        assertThat(fallen.activeAt(T0.plusMinutes(5).plusSeconds(1), PAUSE)).isFalse();
        assertThat(fallen.endAt(T0.plusMinutes(20))).isEqualTo(T0.plusMinutes(4));

        sut.flow(3, T0.plusMinutes(5).plusSeconds(1));

        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW)).isEqualTo(new FlowRun(T0.plusMinutes(5).plusSeconds(1),
            T0.plusMinutes(5).plusSeconds(1), true, 3, T0.plusMinutes(4)));
    }

    //a fall below the high bound with the water still running ends the high run, not the continuous one
    @Test
    void should_judge_each_run_by_its_own_bound() {
        sut.flow(30, T0);
        sut.flow(10, T0.plusMinutes(2));

        assertThat(sut.current().run(Alarm.HIGH_FLOW).above()).isFalse();
        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW).above()).isTrue();
    }

    //reports further apart than the staleness and the minute of the pass: the valve was not heard between them
    @Test
    void should_not_join_flows_across_a_silence() {
        sut.flow(4, T0);
        sut.flow(4, T0.plusMinutes(6).plusSeconds(1));

        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW)).isEqualTo(new FlowRun(T0.plusMinutes(6).plusSeconds(1),
            T0.plusMinutes(6).plusSeconds(1), true, 4, T0));
    }

    //the last will, another valve: what was flowing stopped where the valve was last heard, the flow not known
    @Test
    void should_stop_every_run_at_the_last_report_when_told_to_forget() {
        sut.flow(4, T0);
        sut.flow(30, T0.plusMinutes(1));
        sut.forget();

        assertThat(sut.current().litres()).isNull();
        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW)).isEqualTo(new FlowRun(T0, T0.plusMinutes(1), false, 30,
            null));
        assertThat(sut.current().run(Alarm.HIGH_FLOW).above()).isFalse();
    }

    @Test
    void should_know_nothing_when_told_to_forget_before_a_report() {
        sut.forget();

        assertThat(sut.current()).isEqualTo(FlowTrack.Flows.NONE);
    }

    //a read and a message crossed: the report that arrived later holds
    @Test
    void should_keep_the_later_report() {
        sut.flow(4, T0.plusSeconds(1));
        sut.flow(0, T0);

        assertThat(sut.current().litres()).isEqualTo(4);
        assertThat(sut.current().run(Alarm.CONTINUOUS_FLOW).above()).isTrue();
    }
}
