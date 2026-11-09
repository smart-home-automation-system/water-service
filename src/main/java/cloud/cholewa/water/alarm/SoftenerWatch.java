package cloud.cholewa.water.alarm;

import cloud.cholewa.water.database.model.WaterAlertEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

//What the monitor of the softener holds, in memory (HAS-399) - as AlarmWatch of the valve: the decisions are made here
//first, the database follows.
//loaded - the open episodes of the softener were read after the start; nothing is decided before.
//open - the episode of each alarm that lasts (its id null until its row was stored).
//unsaved - the rows the database did not take yet, tried again with every pass; one an episode, the newest.
//lastHeard - the newest reading the cloud gave of the softener online; the start of the pod until there was one.
//heard - whether lastHeard is a reading: the start of the pod counts for the silence, never for a return - a restart while
//the softener is off would otherwise end the episode it took up
record SoftenerWatch(
    boolean loaded,
    Map<SoftenerAlarm, WaterAlertEntity> open,
    List<WaterAlertEntity> unsaved,
    LocalDateTime lastHeard,
    boolean heard
) {

    SoftenerWatch {
        final Map<SoftenerAlarm, WaterAlertEntity> copy = new EnumMap<>(SoftenerAlarm.class);
        open.forEach((alarm, row) -> copy.put(alarm, Objects.requireNonNull(row)));
        open = Map.copyOf(copy);
        unsaved = List.copyOf(unsaved);
        Objects.requireNonNull(lastHeard);
    }

    static SoftenerWatch notLoaded(final LocalDateTime startedAt) {
        return new SoftenerWatch(false, Map.of(), List.of(), startedAt, false);
    }

    SoftenerWatch loaded(final Map<SoftenerAlarm, WaterAlertEntity> episodes) {
        return new SoftenerWatch(true, episodes, List.of(), lastHeard, heard);
    }

    //a row changed by a decision: held open until it is closed, and stored with the next pass
    SoftenerWatch with(final SoftenerAlarm alarm, final WaterAlertEntity row) {
        final Map<SoftenerAlarm, WaterAlertEntity> nextOpen = new EnumMap<>(SoftenerAlarm.class);
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
        return new SoftenerWatch(loaded, nextOpen, nextUnsaved, lastHeard, heard);
    }

    SoftenerWatch heard(final LocalDateTime at) {
        return at == null || !at.isAfter(lastHeard) ? this : new SoftenerWatch(loaded, open, unsaved, at, true);
    }

    //the row the database took: no longer unsaved unless a newer one of its episode came meanwhile, and the open episode
    //gets its id
    SoftenerWatch stored(final WaterAlertEntity row, final WaterAlertEntity saved) {
        final List<WaterAlertEntity> nextUnsaved = unsaved.stream()
            .filter(waiting -> !waiting.equals(row))
            .map(waiting -> waiting.id() == null && sameEpisode(waiting, saved) ? waiting.withId(saved.id()) : waiting)
            .toList();
        final Map<SoftenerAlarm, WaterAlertEntity> nextOpen = new EnumMap<>(SoftenerAlarm.class);
        open.forEach((alarm, held) -> nextOpen.put(alarm,
            held.id() == null && sameEpisode(held, saved) ? held.withId(saved.id()) : held));
        return new SoftenerWatch(loaded, nextOpen, nextUnsaved, lastHeard, heard);
    }

    private static boolean sameEpisode(final WaterAlertEntity one, final WaterAlertEntity other) {
        return one.kind().equals(other.kind()) && one.openedAt().equals(other.openedAt());
    }
}
