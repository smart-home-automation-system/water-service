package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.softener.SoftenerReading;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

//What the monitor of the softener decides (HAS-399): no alert before the bound, one at it, the reminder - the salt once a
//day, the rest hourly - the info on the return with how long it lasted; unknown is never an alarm
class SoftenerAlarmRulesTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final Duration OFFLINE_AFTER = Duration.ofMinutes(30);
    private static final Duration HOURLY = Duration.ofHours(1);
    private static final SoftenerReading.Alerts QUIET = new SoftenerReading.Alerts(false, false, false, false, false, false);

    private static SoftenerReading reading(final Boolean online, final SoftenerReading.Alerts alerts) {
        return new SoftenerReading(online, 102.0, 503.0, 2218.0, 425408.0, 0.0, 10.0, 5, 0, 156, 12.0, 917, -70, alerts);
    }

    private static SoftenerReading.Alerts lowSalt() {
        return new SoftenerReading.Alerts(true, false, false, false, false, false);
    }

    private static SoftenerReading.Alerts leak() {
        return new SoftenerReading.Alerts(false, true, false, false, false, false);
    }

    private static SoftenerAlarmRules.Decision decide(final SoftenerWatch held, final SoftenerReading reading,
                                                      final LocalDateTime measuredAt, final LocalDateTime now) {
        return SoftenerAlarmRules.decide(held, new SoftenerAlarmRules.Readings(reading, measuredAt), now, OFFLINE_AFTER,
            HOURLY);
    }

    private static SoftenerWatch loaded() {
        return SoftenerWatch.notLoaded(T0).loaded(Map.of());
    }

    //what the monitor does once a notice was published
    private static SoftenerWatch published(final SoftenerAlarmRules.Decision decision) {
        SoftenerWatch next = decision.next();
        for (final SoftenerAlarmRules.Notice notice : decision.notices()) {
            next = next.with(notice.alarm(), notice.row());
        }
        return next;
    }

    @Test
    void should_tell_nothing_of_a_quiet_softener() {
        final SoftenerAlarmRules.Decision decision = decide(loaded(), reading(true, QUIET), T0, T0.plusMinutes(1));

        assertThat(decision.notices()).isEmpty();
        assertThat(decision.next().lastHeard()).isEqualTo(T0);
    }

    @Test
    void should_alert_at_once_of_a_flag_the_softener_raises() {
        final SoftenerAlarmRules.Decision decision = decide(loaded(), reading(true, leak()), T0, T0.plusMinutes(1));

        assertThat(decision.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.ALERT);
            assertThat(notice.alarm()).isEqualTo(SoftenerAlarm.LEAK);
            assertThat(notice.text()).isEqualTo("The water softener reports a leak (since 10:00).");
            assertThat(notice.row()).isEqualTo(new WaterAlertEntity(null, "softener leak", T0, T0.plusMinutes(1), null));
        });
    }

    @Test
    void should_word_the_salt_with_what_is_left() {
        final SoftenerAlarmRules.Decision decision = decide(loaded(), reading(true, lowSalt()), T0, T0.plusMinutes(1));

        assertThat(decision.notices()).extracting(SoftenerAlarmRules.Notice::text)
            .containsExactly("Low salt in the water softener: 10 % left, about 5 days to run out (since 10:00).");
    }

    //the leak is reminded every hour, the salt once a day
    @Test
    void should_remind_the_leak_hourly_and_the_salt_daily() {
        final SoftenerAlarmRules.Decision first = decide(loaded(),
            reading(true, new SoftenerReading.Alerts(true, true, false, false, false, false)), T0, T0);
        SoftenerWatch held = published(first);

        assertThat(decide(held, reading(true, new SoftenerReading.Alerts(true, true, false, false, false, false)),
            T0.plusMinutes(59), T0.plusMinutes(59)).notices()).isEmpty();

        final SoftenerAlarmRules.Decision hour = decide(held,
            reading(true, new SoftenerReading.Alerts(true, true, false, false, false, false)), T0.plusHours(1),
            T0.plusHours(1));
        assertThat(hour.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.REMINDER);
            assertThat(notice.text()).isEqualTo("Still: the water softener reports a leak - since 10:00 (1 h 0 min).");
        });
        held = published(hour);

        final SoftenerAlarmRules.Decision day = decide(held,
            reading(true, new SoftenerReading.Alerts(true, true, false, false, false, false)), T0.plusDays(1),
            T0.plusDays(1));
        assertThat(day.notices()).extracting(SoftenerAlarmRules.Notice::text).containsExactly(
            "Still low salt in the water softener: 10 % left, about 5 days to run out - since 9 Nov 10:00 (1 d 0 h).",
            "Still: the water softener reports a leak - since 9 Nov 10:00 (1 d 0 h).");
    }

    @Test
    void should_tell_the_return_with_how_long_it_lasted() {
        final SoftenerWatch held = published(decide(loaded(), reading(true, leak()), T0, T0));

        final SoftenerAlarmRules.Decision decision = decide(held, reading(true, QUIET), T0.plusMinutes(25),
            T0.plusMinutes(26));

        assertThat(decision.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.text()).isEqualTo(
                "Over: the water softener reports a leak no more - from 10:00 to 10:25 (25 min).");
            assertThat(notice.row().closedAt()).isEqualTo(T0.plusMinutes(25));
        });
        assertThat(published(decision).open()).isEmpty();
    }

    //the salt refilled says how much there is now
    @Test
    void should_tell_the_salt_refilled() {
        final SoftenerWatch held = published(decide(loaded(), reading(true, lowSalt()), T0, T0));
        final SoftenerReading refilled = new SoftenerReading(true, 102.0, 503.0, 2218.0, 425408.0, 0.0, 80.0, 60, 0, 156,
            12.0, 917, -70, QUIET);

        assertThat(decide(held, refilled, T0.plusHours(2), T0.plusHours(2)).notices())
            .extracting(SoftenerAlarmRules.Notice::text)
            .containsExactly("The salt of the water softener refilled: 80 % - low from 10:00 to 12:00 (2 h 0 min).");
    }

    //an alert that never got through is named so by the return
    @Test
    void should_say_the_alert_did_not_get_through() {
        final SoftenerWatch held = decide(loaded(), reading(true, leak()), T0, T0).next();

        assertThat(decide(held, reading(true, QUIET), T0.plusMinutes(5), T0.plusMinutes(5)).notices())
            .extracting(SoftenerAlarmRules.Notice::text)
            .containsExactly("Over: the water softener reports a leak no more - from 10:00 to 10:05 (5 min), the alert "
                + "did not get through.");
    }

    //an alert not published is the alert again with the next pass
    @Test
    void should_alert_again_while_the_alert_did_not_get_through() {
        final SoftenerWatch held = decide(loaded(), reading(true, leak()), T0, T0).next();

        assertThat(decide(held, reading(true, leak()), T0, T0.plusMinutes(1)).notices())
            .extracting(SoftenerAlarmRules.Notice::type)
            .containsExactly(AlarmRules.Type.ALERT);
    }

    //a flag the reading leaves out decides nothing - neither an alert nor a return
    @Test
    void should_decide_nothing_of_a_flag_not_known() {
        final SoftenerReading.Alerts unknown = new SoftenerReading.Alerts(null, null, null, null, null, null);
        final SoftenerWatch held = published(decide(loaded(), reading(true, leak()), T0, T0));

        assertThat(decide(loaded(), reading(true, unknown), T0, T0).notices()).isEmpty();
        assertThat(decide(held, reading(true, unknown), T0.plusMinutes(5), T0.plusMinutes(5)).notices()).isEmpty();
    }

    //the flags of a softener the cloud says is offline are what it said before it left
    @Test
    void should_not_judge_the_flags_of_a_softener_offline() {
        final SoftenerWatch held = published(decide(loaded(), reading(true, leak()), T0, T0));

        assertThat(decide(loaded(), reading(false, leak()), T0, T0).notices()).isEmpty();
        assertThat(decide(held, reading(false, QUIET), T0.plusMinutes(5), T0.plusMinutes(5)).notices()).isEmpty();
    }

    @Test
    void should_not_alert_of_the_silence_before_the_bound() {
        assertThat(decide(loaded(), null, null, T0.plusMinutes(29)).notices()).isEmpty();
    }

    //no reading since the start of the pod for offline-after - the cloud never answered
    @Test
    void should_alert_of_the_silence_at_the_bound() {
        final SoftenerAlarmRules.Decision decision = decide(loaded(), null, null, T0.plusMinutes(30));

        assertThat(decision.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.alarm()).isEqualTo(SoftenerAlarm.OFFLINE);
            assertThat(notice.text())
                .isEqualTo("The water softener is not heard from the iQua cloud: last heard at 10:00 (30 min ago).");
            assertThat(notice.row().openedAt()).isEqualTo(T0);
        });
    }

    //online false in the reading is a silence too
    @Test
    void should_count_a_softener_the_cloud_says_is_offline_as_silent() {
        final SoftenerWatch heard = decide(loaded(), reading(true, QUIET), T0, T0).next();

        assertThat(decide(heard, reading(false, QUIET), T0.plusMinutes(29), T0.plusMinutes(29)).notices()).isEmpty();
        assertThat(decide(heard, reading(false, QUIET), T0.plusMinutes(30), T0.plusMinutes(30)).notices())
            .extracting(SoftenerAlarmRules.Notice::alarm)
            .containsExactly(SoftenerAlarm.OFFLINE);
    }

    @Test
    void should_remind_of_the_silence_hourly_and_tell_the_return() {
        SoftenerWatch held = published(decide(loaded(), null, null, T0.plusMinutes(30)));

        assertThat(decide(held, null, null, T0.plusMinutes(89)).notices()).isEmpty();
        final SoftenerAlarmRules.Decision reminder = decide(held, null, null, T0.plusMinutes(90));
        assertThat(reminder.notices()).extracting(SoftenerAlarmRules.Notice::text)
            .containsExactly("Still: the water softener is not heard from the iQua cloud - since 10:00 (1 h 30 min).");
        held = published(reminder);

        final SoftenerAlarmRules.Decision back = decide(held, reading(true, QUIET), T0.plusMinutes(100),
            T0.plusMinutes(101));
        assertThat(back.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.type()).isEqualTo(AlarmRules.Type.INFO);
            assertThat(notice.text()).isEqualTo(
                "The water softener is heard from the iQua cloud again - not heard from 10:00 to 11:40 (1 h 40 min).");
        });
        assertThat(published(back).open()).isEmpty();
    }

    //a restart while the softener is off: the start of the pod is no return - the episode it took up goes on
    @Test
    void should_not_take_the_start_of_the_pod_for_a_return() {
        final WaterAlertEntity before = new WaterAlertEntity(3L, "softener offline", T0.minusHours(2), T0.minusMinutes(30),
            null);
        final SoftenerWatch restarted = SoftenerWatch.notLoaded(T0).loaded(Map.of(SoftenerAlarm.OFFLINE, before));

        assertThat(decide(restarted, null, null, T0.plusMinutes(1)).notices()).isEmpty();
        assertThat(decide(restarted, null, null, T0.plusMinutes(30)).notices()).extracting(SoftenerAlarmRules.Notice::type)
            .containsExactly(AlarmRules.Type.REMINDER);
        assertThat(decide(restarted, reading(true, QUIET), T0.plusMinutes(2), T0.plusMinutes(2)).notices())
            .extracting(SoftenerAlarmRules.Notice::type)
            .containsExactly(AlarmRules.Type.INFO);
    }

    //each alarm has its own level and reminder
    @Test
    void should_alert_at_the_level_of_each_alarm() {
        assertThat(List.of(SoftenerAlarm.values()))
            .filteredOn(SoftenerAlarm::error)
            .containsExactly(SoftenerAlarm.LEAK, SoftenerAlarm.SHUTOFF_VALVE, SoftenerAlarm.RESIN, SoftenerAlarm.ERROR);
        assertThat(List.of(SoftenerAlarm.values()))
            .filteredOn(SoftenerAlarm::daily)
            .containsExactly(SoftenerAlarm.LOW_SALT);
    }
}
