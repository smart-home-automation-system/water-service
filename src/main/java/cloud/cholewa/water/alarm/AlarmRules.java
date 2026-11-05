package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

//What the monitor of the alarms decides (HAS-319), without I/O - pinned by AlarmRulesTest. The patterns of the house
//(HAS-109, HAS-237, HAS-264, HAS-332):
//- Offline: the valve not heard for offlineAfter opens an episode at the time it was last heard - one alert; a reminder
//  every reminderEvery while it stays so; heard after the episode opened is the return - an info, closed at that report.
//- No mains / frost: the pressure / the water temperature below its bound in every pass for its time opens an episode at
//  the first of them - one alert; a reminder every reminderEvery; at or above the bound in every pass for the same time
//  is the return - an info, closed at the first of them (a temperature wavering about 3 degrees would otherwise alert
//  and return every few minutes). A value not known (not fresh) is never low: it breaks both counts, closes nothing.
//An alert that did not get through is tried again with every pass, and one that never did is named so by the return.
//The opening of an episode is held at once; what a notice changes (told, closed) only once it was published - the
//monitor applies the row of a notice it published
final class AlarmRules {

    private AlarmRules() {
    }

    //pressure - kPa, temperature - degrees C, each fresh or null; heardAt - the newest report of the valve or null
    record Readings(Double pressure, Double temperature, LocalDateTime heardAt) {
    }

    record Decision(AlarmWatch next, List<Notice> notices) {
    }

    //row - the episode as it is once the notice was published
    record Notice(Type type, Alarm alarm, String text, WaterAlertEntity row) {
    }

    enum Type {
        ALERT, REMINDER, INFO
    }

    static Decision decide(final AlarmWatch held, final Readings readings, final LocalDateTime now,
                           final AlarmProperties properties) {
        final List<Notice> notices = new ArrayList<>();
        AlarmWatch next = held.heard(readings.heardAt());
        next = offline(next, now, properties, notices);
        next = threshold(next, Alarm.NO_MAINS, readings.pressure(), properties.pressureLowBelow(),
            properties.pressureLowAfter(), now, properties, notices);
        next = threshold(next, Alarm.FROST, readings.temperature(), properties.frostBelow(), properties.frostAfter(), now,
            properties, notices);
        return new Decision(next, List.copyOf(notices));
    }

    private static AlarmWatch offline(final AlarmWatch held, final LocalDateTime now, final AlarmProperties properties,
                                      final List<Notice> notices) {
        final WaterAlertEntity open = held.open().get(Alarm.OFFLINE);
        final boolean silent = !held.lastHeard().plus(properties.offlineAfter()).isAfter(now);
        if (silent && open == null) {
            final WaterAlertEntity opened = opened(Alarm.OFFLINE, held.lastHeard());
            notices.add(alert(Alarm.OFFLINE, opened, now, null, properties));
            return held.with(Alarm.OFFLINE, opened);
        }
        if (silent) {
            due(Alarm.OFFLINE, open, now, null, properties).ifPresent(notices::add);
            return held;
        }
        if (open != null && held.lastHeard().isAfter(open.openedAt())) {
            final WaterAlertEntity closed = open.closed(held.lastHeard());
            notices.add(new Notice(Type.INFO, Alarm.OFFLINE, AlarmMessages.over(Alarm.OFFLINE, closed, null, properties), closed));
        }
        return held;
    }

    private static AlarmWatch threshold(final AlarmWatch held, final Alarm alarm, final Double value, final double bound,
                                        final Duration after, final LocalDateTime now, final AlarmProperties properties,
                                        final List<Notice> notices) {
        if (value == null) {
            return held.uncounted(alarm);
        }
        final WaterAlertEntity open = held.open().get(alarm);
        if (value >= bound && open == null) {
            return held.uncounted(alarm);
        }
        if (value >= bound) {
            final LocalDateTime since = Optional.ofNullable(held.aboveSince().get(alarm)).orElse(now);
            if (since.plus(after).isAfter(now)) {
                return held.withAbove(alarm, since);
            }
            final WaterAlertEntity closed = open.closed(since);
            notices.add(new Notice(Type.INFO, alarm, AlarmMessages.over(alarm, closed, value, properties), closed));
            //the count stays until the info got through and the episode is no longer open
            return held.withAbove(alarm, since);
        }
        if (open != null) {
            due(alarm, open, now, value, properties).ifPresent(notices::add);
            return held.uncounted(alarm);
        }
        final LocalDateTime since = Optional.ofNullable(held.belowSince().get(alarm)).orElse(now);
        if (since.plus(after).isAfter(now)) {
            return held.withBelow(alarm, since);
        }
        final WaterAlertEntity opened = opened(alarm, since);
        notices.add(alert(alarm, opened, now, value, properties));
        return held.uncounted(alarm).with(alarm, opened);
    }

    //the alert again while it has not got through, or the reminder when it is due
    private static Optional<Notice> due(final Alarm alarm, final WaterAlertEntity open, final LocalDateTime now,
                                        final Double value, final AlarmProperties properties) {
        if (open.lastToldAt() == null) {
            return Optional.of(alert(alarm, open, now, value, properties));
        }
        return open.lastToldAt().plus(properties.reminderEvery()).isAfter(now)
            ? Optional.empty()
            : Optional.of(new Notice(Type.REMINDER, alarm, AlarmMessages.still(alarm, open, now, value, properties),
                open.told(now)));
    }

    private static Notice alert(final Alarm alarm, final WaterAlertEntity open, final LocalDateTime now,
                                final Double value, final AlarmProperties properties) {
        return new Notice(Type.ALERT, alarm, AlarmMessages.alert(alarm, open, now, value, properties), open.told(now));
    }

    private static WaterAlertEntity opened(final Alarm alarm, final LocalDateTime since) {
        return new WaterAlertEntity(null, alarm.wireName(), since, null, null);
    }
}
