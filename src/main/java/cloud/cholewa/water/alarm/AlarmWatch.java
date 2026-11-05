package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

//What the monitor of the alarms holds, in memory (HAS-319) - the decisions are made here first, the database follows.
//loaded - the open episodes were read from the database after the start; nothing is decided before.
//open - the episode of each alarm that lasts (its id null until its row was stored).
//unsaved - the rows the database did not take yet, tried again with every pass; one an episode, the newest.
//belowSince / aboveSince - since when the pressure / the temperature is below its bound with no episode open, or at or
//above it with one open, by the passes that saw it; in memory only, a restart counts anew.
//lastHeard - the newest report of the valve the monitor saw (the last will forgets the values, not this); the start of
//the pod until the valve was heard
record AlarmWatch(
    boolean loaded,
    Map<Alarm, WaterAlertEntity> open,
    List<WaterAlertEntity> unsaved,
    Map<Alarm, LocalDateTime> belowSince,
    Map<Alarm, LocalDateTime> aboveSince,
    LocalDateTime lastHeard
) {

    AlarmWatch {
        open = copy(open);
        unsaved = List.copyOf(unsaved);
        belowSince = copy(belowSince);
        aboveSince = copy(aboveSince);
        Objects.requireNonNull(lastHeard);
    }

    static AlarmWatch notLoaded(final LocalDateTime startedAt) {
        return new AlarmWatch(false, Map.of(), List.of(), Map.of(), Map.of(), startedAt);
    }

    AlarmWatch loaded(final Map<Alarm, WaterAlertEntity> episodes) {
        return new AlarmWatch(true, episodes, List.of(), Map.of(), Map.of(), lastHeard);
    }

    //a row changed by a decision: held open until it is closed, and stored with the next pass
    AlarmWatch with(final Alarm alarm, final WaterAlertEntity row) {
        final Map<Alarm, WaterAlertEntity> nextOpen = new EnumMap<>(Alarm.class);
        nextOpen.putAll(open);
        if (row.closedAt() == null) {
            nextOpen.put(alarm, row);
        } else {
            nextOpen.remove(alarm);
        }
        final List<WaterAlertEntity> nextUnsaved = new ArrayList<>(unsaved.stream()
            .filter(older -> !sameEpisode(older, row))
            .toList());
        nextUnsaved.add(row);
        return new AlarmWatch(loaded, nextOpen, nextUnsaved, belowSince, aboveSince, lastHeard);
    }

    AlarmWatch withBelow(final Alarm alarm, final LocalDateTime since) {
        return new AlarmWatch(loaded, open, unsaved, put(belowSince, alarm, since), aboveSince, lastHeard);
    }

    AlarmWatch withoutBelow(final Alarm alarm) {
        return new AlarmWatch(loaded, open, unsaved, remove(belowSince, alarm), aboveSince, lastHeard);
    }

    AlarmWatch withAbove(final Alarm alarm, final LocalDateTime since) {
        return new AlarmWatch(loaded, open, unsaved, belowSince, put(aboveSince, alarm, since), lastHeard);
    }

    AlarmWatch withoutAbove(final Alarm alarm) {
        return new AlarmWatch(loaded, open, unsaved, belowSince, remove(aboveSince, alarm), lastHeard);
    }

    //neither count: a reading not known breaks both
    AlarmWatch uncounted(final Alarm alarm) {
        return withoutBelow(alarm).withoutAbove(alarm);
    }

    AlarmWatch heard(final LocalDateTime at) {
        return at == null || !at.isAfter(lastHeard) ? this : new AlarmWatch(loaded, open, unsaved, belowSince, aboveSince, at);
    }

    //the row the database took: no longer unsaved unless a newer one of its episode came meanwhile, and the open
    //episode gets its id
    AlarmWatch stored(final WaterAlertEntity row, final WaterAlertEntity saved) {
        final List<WaterAlertEntity> nextUnsaved = unsaved.stream()
            .filter(waiting -> !waiting.equals(row))
            .map(waiting -> waiting.id() == null && sameEpisode(waiting, saved) ? waiting.withId(saved.id()) : waiting)
            .toList();
        final Map<Alarm, WaterAlertEntity> nextOpen = new EnumMap<>(Alarm.class);
        open.forEach((alarm, held) -> nextOpen.put(alarm,
            held.id() == null && sameEpisode(held, saved) ? held.withId(saved.id()) : held));
        return new AlarmWatch(loaded, nextOpen, nextUnsaved, belowSince, aboveSince, lastHeard);
    }

    private static boolean sameEpisode(final WaterAlertEntity one, final WaterAlertEntity other) {
        return one.kind().equals(other.kind()) && one.openedAt().equals(other.openedAt());
    }

    private static Map<Alarm, LocalDateTime> put(final Map<Alarm, LocalDateTime> map, final Alarm alarm,
                                                 final LocalDateTime since) {
        final Map<Alarm, LocalDateTime> next = new EnumMap<>(Alarm.class);
        next.putAll(map);
        next.put(alarm, since);
        return next;
    }

    private static Map<Alarm, LocalDateTime> remove(final Map<Alarm, LocalDateTime> map, final Alarm alarm) {
        final Map<Alarm, LocalDateTime> next = new EnumMap<>(Alarm.class);
        next.putAll(map);
        next.remove(alarm);
        return next;
    }

    private static <V> Map<Alarm, V> copy(final Map<Alarm, V> map) {
        final Map<Alarm, V> copy = new EnumMap<>(Alarm.class);
        map.forEach((alarm, value) -> copy.put(alarm, Objects.requireNonNull(value)));
        return Map.copyOf(copy);
    }
}
