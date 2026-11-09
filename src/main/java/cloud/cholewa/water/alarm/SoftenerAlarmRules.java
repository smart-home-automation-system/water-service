package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;
import cloud.cholewa.water.softener.SoftenerReading;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

//What the monitor of the softener decides (HAS-399), without I/O - pinned by SoftenerAlarmRulesTest. The patterns of the
//house (HAS-109, HAS-237, HAS-264, HAS-319):
//- A flag of the softener (low salt, leak, excessive use, shutoff valve, resin, error) true in the newest reading opens an
//  episode at that reading - one alert; a reminder while it lasts (the salt once a day, the rest every
//  water.alarm.reminder-every); the flag false in a reading is the return - an info, closed at that reading. A flag the
//  reading leaves out, no reading, or a reading of the softener offline decides nothing - unknown is never an alarm, and
//  the flags of a softener off Wi-Fi are what it said before it left.
//- Offline: no reading of the softener online - it said online false, or the cloud did not answer - for
//  water.softener.offline-after opens an episode where it was last heard; heard online after the episode opened is the
//  return, closed at that reading - a reading, never the start of the pod, which counts for the silence only.
//An alert that did not get through is tried again with every pass, and one that never did is named so by the return. As
//in AlarmRules, what a notice changes is held only once it was published
final class SoftenerAlarmRules {

    static final Duration DAILY = Duration.ofDays(1);

    private static final Map<SoftenerAlarm, Function<SoftenerReading.Alerts, Boolean>> FLAGS = Map.of(
        SoftenerAlarm.LOW_SALT, SoftenerReading.Alerts::lowSalt,
        SoftenerAlarm.LEAK, SoftenerReading.Alerts::leak,
        SoftenerAlarm.EXCESSIVE_USE, SoftenerReading.Alerts::excessiveWaterUse,
        SoftenerAlarm.SHUTOFF_VALVE, SoftenerReading.Alerts::shutoffValve,
        SoftenerAlarm.RESIN, SoftenerReading.Alerts::resin,
        SoftenerAlarm.ERROR, SoftenerReading.Alerts::error);

    private SoftenerAlarmRules() {
    }

    //reading - the newest reading of the softener and when it was taken, both null before the first
    record Readings(SoftenerReading reading, LocalDateTime measuredAt) {
    }

    record Decision(SoftenerWatch next, List<Notice> notices) {
    }

    //row - the episode as it is once the notice was published
    record Notice(AlarmRules.Type type, SoftenerAlarm alarm, String text, WaterAlertEntity row) {
    }

    static Decision decide(final SoftenerWatch held, final Readings readings, final LocalDateTime now,
                           final Duration offlineAfter, final Duration reminderEvery) {
        final List<Notice> notices = new ArrayList<>();
        final boolean online = readings.reading() != null && !Boolean.FALSE.equals(readings.reading().online());
        SoftenerWatch next = online ? held.heard(readings.measuredAt()) : held;
        next = offline(next, readings, now, offlineAfter, reminderEvery, notices);
        for (final SoftenerAlarm alarm : SoftenerAlarm.values()) {
            if (alarm != SoftenerAlarm.OFFLINE) {
                next = flag(next, alarm, online ? readings : null, now, reminderEvery, notices);
            }
        }
        return new Decision(next, List.copyOf(notices));
    }

    private static SoftenerWatch offline(final SoftenerWatch held, final Readings readings, final LocalDateTime now,
                                         final Duration offlineAfter, final Duration reminderEvery,
                                         final List<Notice> notices) {
        final WaterAlertEntity open = held.open().get(SoftenerAlarm.OFFLINE);
        final boolean silent = !held.lastHeard().plus(offlineAfter).isAfter(now);
        if (silent && open == null) {
            final WaterAlertEntity opened = opened(SoftenerAlarm.OFFLINE, held.lastHeard());
            notices.add(alert(SoftenerAlarm.OFFLINE, opened, now, readings.reading()));
            return held.with(SoftenerAlarm.OFFLINE, opened);
        }
        if (silent) {
            due(SoftenerAlarm.OFFLINE, open, now, readings.reading(), reminderEvery).ifPresent(notices::add);
            return held;
        }
        if (open != null && held.heard() && held.lastHeard().isAfter(open.openedAt())) {
            final WaterAlertEntity closed = open.closed(held.lastHeard());
            notices.add(new Notice(AlarmRules.Type.INFO, SoftenerAlarm.OFFLINE,
                SoftenerMessages.over(SoftenerAlarm.OFFLINE, closed, readings.reading()), closed));
        }
        return held;
    }

    //readings null - nothing is known of the flags now: an open episode is reminded of, nothing opens or closes
    private static SoftenerWatch flag(final SoftenerWatch held, final SoftenerAlarm alarm, final Readings readings,
                                      final LocalDateTime now, final Duration reminderEvery, final List<Notice> notices) {
        final WaterAlertEntity open = held.open().get(alarm);
        final SoftenerReading reading = readings == null ? null : readings.reading();
        final Boolean raised = Optional.ofNullable(reading)
            .map(SoftenerReading::alerts)
            .map(FLAGS.get(alarm))
            .orElse(null);
        if (raised == null) {
            Optional.ofNullable(open)
                .flatMap(episode -> due(alarm, episode, now, reading, reminderEvery))
                .ifPresent(notices::add);
            return held;
        }
        if (raised && open == null) {
            final WaterAlertEntity opened = opened(alarm, readings.measuredAt());
            notices.add(alert(alarm, opened, now, reading));
            return held.with(alarm, opened);
        }
        if (raised) {
            due(alarm, open, now, reading, reminderEvery).ifPresent(notices::add);
            return held;
        }
        if (open != null) {
            final LocalDateTime end = readings.measuredAt().isAfter(open.openedAt()) ? readings.measuredAt() : open.openedAt();
            final WaterAlertEntity closed = open.closed(end);
            notices.add(new Notice(AlarmRules.Type.INFO, alarm, SoftenerMessages.over(alarm, closed, reading), closed));
        }
        return held;
    }

    //the alert again while it has not got through, or the reminder when it is due
    private static Optional<Notice> due(final SoftenerAlarm alarm, final WaterAlertEntity open, final LocalDateTime now,
                                        final SoftenerReading reading, final Duration reminderEvery) {
        if (open.lastToldAt() == null) {
            return Optional.of(alert(alarm, open, now, reading));
        }
        final Duration every = alarm.daily() ? DAILY : reminderEvery;
        return open.lastToldAt().plus(every).isAfter(now)
            ? Optional.empty()
            : Optional.of(new Notice(AlarmRules.Type.REMINDER, alarm, SoftenerMessages.still(alarm, open, now, reading),
                open.told(now)));
    }

    private static Notice alert(final SoftenerAlarm alarm, final WaterAlertEntity open, final LocalDateTime now,
                                final SoftenerReading reading) {
        return new Notice(AlarmRules.Type.ALERT, alarm, SoftenerMessages.alert(alarm, open, now, reading), open.told(now));
    }

    private static WaterAlertEntity opened(final SoftenerAlarm alarm, final LocalDateTime since) {
        return new WaterAlertEntity(null, alarm.wireName(), since, null, null);
    }
}
