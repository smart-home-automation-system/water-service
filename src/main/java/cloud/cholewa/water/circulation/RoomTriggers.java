package cloud.cholewa.water.circulation;

import cloud.cholewa.water.light.LightRoom;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

//What the reads of light-service trigger (HAS-405): a motion newer than the last one seen in the room, or a light
//switched on - a room read with no light on (anyOn false) and now with one. The first read of a room after the start is
//what was there already and triggers nothing; a read that does not know the motion keeps the last one seen, so a gap of
//light-service never makes an old motion new. Only the pass of the automation calls it (fixedDelay, one at a time)
final class RoomTriggers {

    static final String MOTION = "motion";
    static final String LIGHT = "light";

    private record Seen(LocalDateTime lastMotionAt, Boolean anyOn) {
    }

    private final Map<String, Seen> seen = new HashMap<>();

    synchronized Optional<Trigger> observe(final String room, final LightRoom read) {
        final Seen before = seen.get(room);
        final LocalDateTime motion = newest(before == null ? null : before.lastMotionAt(), read.lastMotionAt());
        seen.put(room, new Seen(motion, read.anyOn()));
        if (before == null) {
            return Optional.empty();
        }
        if (read.lastMotionAt() != null && (before.lastMotionAt() == null || read.lastMotionAt().isAfter(before.lastMotionAt()))) {
            return Optional.of(new Trigger(MOTION, room));
        }
        if (Boolean.FALSE.equals(before.anyOn()) && Boolean.TRUE.equals(read.anyOn())) {
            return Optional.of(new Trigger(LIGHT, room));
        }
        return Optional.empty();
    }

    private static LocalDateTime newest(final LocalDateTime held, final LocalDateTime read) {
        if (held == null) {
            return read;
        }
        return read == null || read.isBefore(held) ? held : read;
    }

    //what started a run: kind - the trigger of circulation_run (motion, light, window), source - the room or the window
    record Trigger(String kind, String source) {
    }
}
