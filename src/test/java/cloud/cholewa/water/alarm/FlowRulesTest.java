package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.presence.HouseOccupancy;
import cloud.cholewa.water.valve.ValveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//The three flow alarms (HAS-320): a high flow, a flow that does not stop, a flow while nobody is at home - each by its
//own run, one episode at a time, an info when the flow stops; nothing while the flow or the house is not known
class FlowRulesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final FlowProperties FLOW = new FlowProperties(25.0, Duration.ofMinutes(3), Duration.ofMinutes(1),
        Duration.ofMinutes(30), 0.5, Duration.ofMinutes(2), false);
    private static final AlarmProperties ALARM = new AlarmProperties(100.0, Duration.ofMinutes(5),
        Duration.ofMinutes(5), 3.0, Duration.ofMinutes(5), Duration.ofHours(1));
    private static final House OCCUPIED = new House(HouseOccupancy.OCCUPIED, null);

    private FlowTrack track;
    private AlarmWatch watch;
    private House house;

    @BeforeEach
    void setUp() {
        track = new FlowTrack(FLOW, new ValveProperties(Duration.ofMinutes(5)));
        watch = AlarmWatch.notLoaded(T0).loaded(Map.of());
        house = OCCUPIED;
    }

    //above 25 l/min for three minutes: the alert at the third, not before
    @Test
    void should_alert_a_high_flow_after_its_time() {
        assertThat(flowing(30, 0, 2)).isEmpty();

        assertThat(flowing(30, 3, 3)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.ALERT);
            assertThat(notice.alarm()).isEqualTo(Alarm.HIGH_FLOW);
            assertThat(notice.text()).isEqualTo("High water flow: 30 l/min since 10:00 (3 min), above 25 l/min.");
        });
        assertThat(watch.open()).containsEntry(Alarm.HIGH_FLOW, new WaterAlertEntity(null, "high flow", T0,
            T0.plusMinutes(3), null));
    }

    //a high flow that fell before its third minute was seen: no alert for water that no longer runs
    @Test
    void should_not_alert_a_high_flow_that_already_fell() {
        flowing(30, 0, 2);
        track.flow(30, T0.plusMinutes(2).plusSeconds(50));
        track.flow(0, T0.plusMinutes(2).plusSeconds(59));

        assertThat(pass(3)).isEmpty();
        assertThat(pass(4)).isEmpty();
    }

    //the valve not heard while it was running: the episode ends where it was last heard, not with the next flow
    @Test
    void should_close_an_episode_where_the_valve_was_last_heard_after_a_silence() {
        flowing(2, 0, 30);
        assertThat(watch.open()).containsKey(Alarm.CONTINUOUS_FLOW);

        assertThat(unknown(35)).isEmpty();
        track.flow(2, T0.plusMinutes(40));

        assertThat(pass(40)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.row().closedAt()).isEqualTo(T0.plusMinutes(30));
        });
    }

    //a high flow that is also continuous: the high flow at its bound, the continuous flow at its own
    @Test
    void should_raise_the_high_flow_first_and_the_continuous_flow_at_its_own_bound() {
        assertThat(flowing(30, 0, 3)).extracting(AlarmRules.Notice::alarm).containsExactly(Alarm.HIGH_FLOW);
        assertThat(flowing(30, 4, 29)).isEmpty();

        assertThat(flowing(30, 30, 30)).singleElement().satisfies(notice -> {
            assertThat(notice.alarm()).isEqualTo(Alarm.CONTINUOUS_FLOW);
            assertThat(notice.text())
                .isEqualTo("Continuous water flow: running without a pause since 10:00 (30 min), 30 l/min now.");
        });
    }

    //the flow stops: an info, closed where it fell, with the most of the run
    @Test
    void should_tell_the_end_of_a_high_flow_when_it_stops() {
        flowing(30, 0, 3);
        track.flow(40, T0.plusMinutes(4));
        track.flow(0, T0.plusMinutes(5));

        assertThat(pass(5)).isEmpty();
        assertThat(pass(6)).isEmpty();

        assertThat(pass(7)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.text())
                .isEqualTo("High water flow over: above 25 l/min from 10:00 to 10:05 (5 min), at most 40 l/min.");
            assertThat(notice.row().closedAt()).isEqualTo(T0.plusMinutes(5));
        });
        assertThat(watch.open()).doesNotContainKey(Alarm.HIGH_FLOW);
    }

    //a flow above zero with stops of 50 s for half an hour is one continuous flow
    @Test
    void should_alert_a_continuous_flow_across_short_pauses() {
        for (int minute = 0; minute < 30; minute++) {
            track.flow(2, T0.plusMinutes(minute));
            track.flow(0, T0.plusMinutes(minute).plusSeconds(10));
            pass(minute);
        }
        track.flow(2, T0.plusMinutes(30));

        assertThat(pass(30)).extracting(AlarmRules.Notice::alarm).containsExactly(Alarm.CONTINUOUS_FLOW);
    }

    //a stop of more than a minute ends it: no continuous flow
    @Test
    void should_not_alert_a_flow_with_longer_pauses() {
        for (int minute = 0; minute <= 40; minute += 3) {
            track.flow(2, T0.plusMinutes(minute));
            track.flow(0, T0.plusMinutes(minute).plusSeconds(30));
            assertThat(pass(minute)).isEmpty();
        }
    }

    //a new run between two passes: the episode of the run before is over, closed where that run fell
    @Test
    void should_close_an_episode_when_a_new_run_starts_between_two_passes() {
        flowing(2, 0, 30);
        track.flow(0, T0.plusMinutes(30).plusSeconds(10));
        track.flow(2, T0.plusMinutes(31).plusSeconds(20));

        assertThat(pass(31)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.alarm()).isEqualTo(Alarm.CONTINUOUS_FLOW);
            assertThat(notice.text()).isEqualTo("Continuous water flow over: it ran from 10:00 to 10:30 (30 min).");
        });
    }

    //water running in a house empty for two minutes - presence-service read every minute
    @Test
    void should_alert_a_flow_in_an_empty_house() {
        house = new House(HouseOccupancy.EMPTY, T0.minusMinutes(1));
        assertThat(flowing(3, 0, 0)).isEmpty();

        assertThat(flowing(3, 1, 1)).singleElement().satisfies(notice -> {
            assertThat(notice.alarm()).isEqualTo(Alarm.EMPTY_HOUSE_FLOW);
            assertThat(notice.text()).isEqualTo(
                "Water running while nobody is at home: 3 l/min since 10:00 (1 min), the house empty since 09:59.");
        });
    }

    //the noise of the sensor is no flow
    @Test
    void should_not_alert_the_noise_in_an_empty_house() {
        house = new House(HouseOccupancy.EMPTY, T0.minusHours(1));

        assertThat(flowing(0.4, 0, 5)).isEmpty();
    }

    //unknown is never empty: a member not seen for a while, or presence-service not read - the time it was empty kept
    @Test
    void should_not_alert_while_the_house_is_not_known() {
        house = new House(HouseOccupancy.UNKNOWN, T0.minusHours(1));

        assertThat(flowing(3, 0, 10)).isEmpty();
    }

    @Test
    void should_not_alert_while_somebody_is_at_home() {
        assertThat(flowing(3, 0, 10)).isEmpty();
    }

    //the empty house ends with the flow, not with the return of a member: who came back may not know of it
    @Test
    void should_tell_the_end_of_a_flow_in_an_empty_house_when_the_flow_stops() {
        house = new House(HouseOccupancy.EMPTY, T0.minusHours(1));
        flowing(3, 0, 0);
        house = OCCUPIED;

        assertThat(flowing(3, 1, 3)).isEmpty();
        track.flow(0, T0.plusMinutes(4));

        assertThat(pass(6)).singleElement().satisfies(notice -> assertThat(notice.text())
            .isEqualTo("Water stopped in the empty house: it ran from 10:00 to 10:04 (4 min), at most 3 l/min."));
    }

    //the flow not known: nothing opens, nothing closes - the reminders go on
    @Test
    void should_decide_nothing_while_the_flow_is_not_known() {
        flowing(30, 0, 3);
        track.forget();

        assertThat(unknown(10)).isEmpty();
        assertThat(watch.open()).containsKey(Alarm.HIGH_FLOW);

        assertThat(unknown(63)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.REMINDER);
            assertThat(notice.text()).isEqualTo(
                "Still a high water flow: the flow not known now since 10:00 (1 h 3 min), above 25 l/min.");
        });
    }

    //a reminder every hour while it lasts
    @Test
    void should_remind_every_hour() {
        flowing(30, 0, 3);

        assertThat(flowing(30, 4, 62)).extracting(AlarmRules.Notice::alarm).containsExactly(Alarm.CONTINUOUS_FLOW);
        assertThat(flowing(30, 63, 63)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.REMINDER);
            assertThat(notice.alarm()).isEqualTo(Alarm.HIGH_FLOW);
        });
    }

    //after a restart the episode goes on with the first run the track sees, and ends when it stops
    @Test
    void should_take_up_an_episode_after_a_restart() {
        watch = AlarmWatch.notLoaded(T0).loaded(Map.of(Alarm.CONTINUOUS_FLOW,
            new WaterAlertEntity(4L, "continuous flow", T0.minusHours(1), T0.minusMinutes(10), null)));

        assertThat(flowing(2, 0, 5)).isEmpty();
        track.flow(0, T0.plusMinutes(6));

        assertThat(pass(8)).singleElement().satisfies(notice -> assertThat(notice.row().closedAt())
            .isEqualTo(T0.plusMinutes(6)));
    }

    //after a restart with the water stopped: nothing above the bound since the track knows the flow
    @Test
    void should_close_an_episode_of_a_flow_that_stopped_during_a_restart() {
        watch = AlarmWatch.notLoaded(T0).loaded(Map.of(Alarm.HIGH_FLOW,
            new WaterAlertEntity(4L, "high flow", T0.minusHours(1), T0.minusMinutes(10), null)));

        assertThat(flowing(0, 0, 0)).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.row().closedAt()).isEqualTo(T0);
        });
    }

    //an alert that did not get through is sent again, and its end says so
    @Test
    void should_alert_again_until_it_got_through() {
        track.flow(30, T0);
        final List<AlarmRules.Notice> first = unpublished(3);
        assertThat(first).extracting(AlarmRules.Notice::type).containsExactly(AlarmRules.Type.ALERT);

        track.flow(0, T0.plusMinutes(3).plusSeconds(30));
        assertThat(unpublished(4)).extracting(AlarmRules.Notice::type).containsExactly(AlarmRules.Type.ALERT);

        assertThat(pass(5)).singleElement().satisfies(notice -> assertThat(notice.text())
            .isEqualTo("High water flow over: above 25 l/min from 10:00 to 10:03 (3 min), at most 30 l/min, "
                + "the alert did not get through."));
    }

    //the flow reported at the start of each minute and a pass after it
    private List<AlarmRules.Notice> flowing(final double litres, final int first, final int last) {
        final List<AlarmRules.Notice> notices = new java.util.ArrayList<>();
        for (int minute = first; minute <= last; minute++) {
            track.flow(litres, T0.plusMinutes(minute));
            notices.addAll(pass(minute));
        }
        return notices;
    }

    //a pass whose notices all got through
    private List<AlarmRules.Notice> pass(final int minute) {
        final AlarmRules.Decision decision = decide(minute, true);
        watch = decision.next();
        decision.notices().forEach(notice -> watch = watch.with(notice.alarm(), notice.row()));
        return decision.notices();
    }

    //a pass whose notices did not get through
    private List<AlarmRules.Notice> unpublished(final int minute) {
        final AlarmRules.Decision decision = decide(minute, true);
        watch = decision.next();
        return decision.notices();
    }

    private List<AlarmRules.Notice> unknown(final int minute) {
        final AlarmRules.Decision decision = decide(minute, false);
        watch = decision.next();
        decision.notices().forEach(notice -> watch = watch.with(notice.alarm(), notice.row()));
        return decision.notices();
    }

    private AlarmRules.Decision decide(final int minute, final boolean known) {
        final FlowTrack.Flows flows = track.current();
        return FlowRules.decide(watch, new FlowRules.Readings(flows, known, known ? flows.litres() : null, house),
            T0.plusMinutes(minute), FLOW, ALARM);
    }
}
