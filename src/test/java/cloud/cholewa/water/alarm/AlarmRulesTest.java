package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//What the monitor of the alarms decides (HAS-319): no alert before the bound, one at the bound, the reminder every hour,
//the info on the return, an alert that did not get through - pass by pass, a minute apart
class AlarmRulesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 5, 10, 0);
    private static final AlarmProperties PROPERTIES = new AlarmProperties(100.0, Duration.ofMinutes(5),
        Duration.ofMinutes(5), 3.0, Duration.ofMinutes(5), Duration.ofHours(1));
    private static final double MAINS = 490.0;
    private static final double WARM = 12.0;

    @Test
    void should_raise_no_alarm_while_the_valve_is_heard_and_its_readings_are_good() {
        AlarmWatch watch = loaded(T0);
        for (int minute = 0; minute < 120; minute++) {
            final AlarmRules.Decision decision = decide(watch, MAINS, WARM, at(minute), minute);
            assertThat(decision.notices()).isEmpty();
            watch = decision.next();
        }
        assertThat(watch.open()).isEmpty();
    }

    //below 1 bar from 10:00: nothing at 10:01 - 10:04, the alert at 10:05, opened at 10:00
    @Test
    void should_alert_no_mains_water_at_its_bound_and_not_before() {
        AlarmWatch watch = loaded(T0);
        for (int minute = 0; minute < 5; minute++) {
            final AlarmRules.Decision decision = decide(watch, 40.0, WARM, at(minute), minute);
            assertThat(decision.notices()).isEmpty();
            watch = decision.next();
        }

        final AlarmRules.Decision alert = decide(watch, 40.0, WARM, at(5), 5);

        assertThat(alert.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.ALERT);
            assertThat(notice.alarm()).isEqualTo(Alarm.NO_MAINS);
            assertThat(notice.text()).isEqualTo(
                "No mains water: the pressure at the water valve is 0.4 bar since 10:00 (5 min), below 1 bar.");
            assertThat(notice.row()).isEqualTo(new WaterAlertEntity(null, "no mains", T0, at(5), null));
        });
        assertThat(alert.next().open().get(Alarm.NO_MAINS).lastToldAt()).isNull();
    }

    //a reading not known is never low: it breaks the count, so the bound is counted again from the next low reading
    @Test
    void should_count_anew_after_a_reading_not_known() {
        AlarmWatch watch = loaded(T0);
        watch = decide(watch, 40.0, WARM, at(0), 0).next();
        watch = decide(watch, 40.0, WARM, at(3), 3).next();
        watch = decide(watch, null, WARM, at(4), 4).next();
        watch = decide(watch, 40.0, WARM, at(5), 5).next();

        assertThat(decide(watch, 40.0, WARM, at(9), 9).notices()).isEmpty();
        assertThat(decide(watch, 40.0, WARM, at(10), 10).notices()).hasSize(1);
    }

    //the reminder every hour from the last telling, the return an info naming how long it lasted, closed now
    @Test
    void should_remind_every_hour_and_tell_the_return() {
        AlarmWatch watch = told(open(Alarm.NO_MAINS, T0), at(5));

        assertThat(decide(watch, 40.0, WARM, at(64), 64).notices()).isEmpty();
        final AlarmRules.Decision reminder = decide(watch, 30.0, WARM, at(65), 65);
        assertThat(reminder.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.REMINDER);
            assertThat(notice.text()).isEqualTo(
                "Still no mains water: the pressure at the water valve is 0.3 bar since 10:00 (1 h 5 min), below 1 bar.");
            assertThat(notice.row().lastToldAt()).isEqualTo(at(65));
        });

        AlarmWatch returning = watch;
        for (int minute = 80; minute < 85; minute++) {
            final AlarmRules.Decision pass = decide(returning, MAINS, WARM, at(minute), minute);
            assertThat(pass.notices()).isEmpty();
            returning = pass.next();
        }
        final AlarmRules.Decision back = decide(returning, MAINS, WARM, at(85), 85);
        assertThat(back.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.text()).isEqualTo("Mains water back: 4.9 bar - below 1 bar from 10:00 to 11:20 (1 h 20 min).");
            assertThat(notice.row().closedAt()).isEqualTo(at(80));
        });
    }

    //an alert that did not get through is tried with every pass, and its return says so
    @Test
    void should_try_an_alert_that_did_not_get_through_again_and_name_it_at_the_return() {
        final AlarmWatch watch = open(Alarm.NO_MAINS, T0);

        assertThat(decide(watch, 40.0, WARM, at(6), 6).notices()).singleElement()
            .satisfies(notice -> assertThat(notice.type()).isEqualTo(AlarmRules.Type.ALERT));
        final AlarmWatch back = decide(watch, MAINS, WARM, at(7), 7).next();
        assertThat(decide(back, MAINS, WARM, at(12), 12).notices()).singleElement()
            .satisfies(notice -> assertThat(notice.text()).isEqualTo(
                "Mains water back: 4.9 bar - below 1 bar from 10:00 to 10:07 (7 min), the alert did not get through."));
    }

    //an open alarm with its reading not known stays as it is - no reminder, no return
    @Test
    void should_neither_remind_nor_close_while_the_reading_is_not_known() {
        final AlarmWatch watch = told(open(Alarm.FROST, T0), at(5));

        assertThat(decide(watch, MAINS, null, at(90), 90).notices()).isEmpty();
        assertThat(decide(watch, MAINS, null, at(90), 90).next().open()).containsKey(Alarm.FROST);
    }

    @Test
    void should_alert_frost_at_its_bound() {
        AlarmWatch watch = loaded(T0);
        for (int minute = 0; minute < 5; minute++) {
            watch = decide(watch, MAINS, 2.5, at(minute), minute).next();
        }

        assertThat(decide(watch, MAINS, 2.5, at(5), 5).notices()).singleElement().satisfies(notice -> {
            assertThat(notice.alarm()).isEqualTo(Alarm.FROST);
            assertThat(notice.text()).isEqualTo("Frost risk: the water at the valve is 2.5 °C since 10:00 (5 min), below 3 °C.");
        });
        assertThat(decide(watch, MAINS, 3.0, at(5), 5).notices()).isEmpty();
    }

    @Test
    void should_tell_the_end_of_a_frost_risk() {
        final AlarmWatch watch = told(open(Alarm.FROST, T0), at(5));

        final AlarmWatch warmer = decide(watch, MAINS, 3.2, at(330), 330).next();

        assertThat(decide(warmer, MAINS, 4.1, at(334), 334).notices()).isEmpty();
        assertThat(decide(warmer, MAINS, 4.1, at(335), 335).notices()).singleElement().satisfies(notice ->
            assertThat(notice.text()).isEqualTo("Frost risk over: 4.1 °C - below 3 °C from 10:00 to 15:30 (5 h 30 min)."));
    }

    //a temperature wavering about the bound neither returns nor alerts again every few minutes
    @Test
    void should_not_return_while_the_temperature_wavers_about_its_bound() {
        AlarmWatch watch = told(open(Alarm.FROST, T0), at(5));
        for (int minute = 10; minute < 40; minute++) {
            final AlarmRules.Decision pass = decide(watch, MAINS, minute % 3 == 0 ? 2.9 : 3.1, at(minute), minute);
            assertThat(pass.notices()).isEmpty();
            watch = pass.next();
        }
        assertThat(watch.open()).containsKey(Alarm.FROST);
    }

    //last heard at 10:00: nothing at 10:04, the alert at 10:05, opened at 10:00; the return at the first new report
    @Test
    void should_alert_a_valve_not_heard_and_tell_its_return() {
        final AlarmWatch watch = loaded(T0);

        assertThat(AlarmRules.decide(watch, readings(null, null, null), at(4), PROPERTIES).notices()).isEmpty();
        final AlarmRules.Decision alert = AlarmRules.decide(watch, readings(null, null, null), at(5), PROPERTIES);
        assertThat(alert.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.alarm()).isEqualTo(Alarm.OFFLINE);
            assertThat(notice.text()).isEqualTo("Not available: the water valve has not been heard since 10:00 (5 min).");
        });

        final AlarmWatch out = alert.next().with(Alarm.OFFLINE, alert.notices().getFirst().row());
        final AlarmRules.Decision back = AlarmRules.decide(out, readings(MAINS, WARM, at(30)), at(31), PROPERTIES);
        assertThat(back.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.text()).isEqualTo("Available again: the water valve - out from 10:00 to 10:30 (30 min).");
            assertThat(notice.row().closedAt()).isEqualTo(at(30));
        });
    }

    //the last will forgets the values, not when the valve was last heard
    @Test
    void should_keep_when_the_valve_was_last_heard_when_its_values_are_forgotten() {
        final AlarmWatch heard = AlarmRules.decide(loaded(T0), readings(MAINS, WARM, at(10)), at(10), PROPERTIES).next();

        final AlarmRules.Decision forgotten = AlarmRules.decide(heard, readings(null, null, null), at(14), PROPERTIES);

        assertThat(forgotten.next().lastHeard()).isEqualTo(at(10));
        assertThat(forgotten.notices()).isEmpty();
        assertThat(AlarmRules.decide(heard, readings(null, null, null), at(15), PROPERTIES).notices()).hasSize(1);
    }

    private static AlarmRules.Decision decide(final AlarmWatch watch, final Double pressure, final Double temperature,
                                              final LocalDateTime now, final int heardMinute) {
        return AlarmRules.decide(watch, readings(pressure, temperature, at(heardMinute)), now, PROPERTIES);
    }

    private static AlarmRules.Readings readings(final Double pressure, final Double temperature,
                                                final LocalDateTime heardAt) {
        return new AlarmRules.Readings(pressure, temperature, heardAt);
    }

    private static AlarmWatch loaded(final LocalDateTime startedAt) {
        return AlarmWatch.notLoaded(startedAt).loaded(Map.of());
    }

    private static AlarmWatch open(final Alarm alarm, final LocalDateTime since) {
        return AlarmWatch.notLoaded(since).loaded(Map.of(alarm, new WaterAlertEntity(1L, alarm.wireName(), since, null, null)));
    }

    private static AlarmWatch told(final AlarmWatch watch, final LocalDateTime at) {
        final Map.Entry<Alarm, WaterAlertEntity> open = watch.open().entrySet().iterator().next();
        return AlarmWatch.notLoaded(watch.lastHeard()).loaded(Map.of(open.getKey(), open.getValue().told(at)));
    }

    private static LocalDateTime at(final int minute) {
        return T0.plusMinutes(minute);
    }
}
