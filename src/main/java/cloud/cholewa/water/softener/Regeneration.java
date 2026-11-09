package cloud.cholewa.water.softener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.stream.Stream;

//The regeneration of the softener as the readings of the cloud tell it (HAS-399) - for the flow alarms of the valve, which
//it would otherwise raise: the softener takes water from the mains while it regenerates, about 0.5 l/min for two hours
//(the continuous flow of 2026-11-09 02:01 - 04:04, the regeneration 02:01:51 - 04:03:46). Without I/O - pinned by
//RegenerationTest.
//startedAt / endedAt - the newest regeneration seen (SoftenerTracker decides from the reading what is one), from the time
//the cloud changed regen_status_enum (its updated_at), the reading itself when it has none; endedAt null while it goes on.
//scheduledAt - regen_time_secs, the time of day the softener regenerates at (02:00 - the clock of the softener,
//current_time_secs, ran 2 min behind the house on 2026-11-08). checkedAt - the newest reading that told the status
public record Regeneration(LocalDateTime startedAt, LocalDateTime endedAt, LocalTime scheduledAt, LocalDateTime checkedAt) {

    public static final Regeneration NONE = new Regeneration(null, null, null, null);

    //a flow started this long before a regeneration was seen is still its flow - the reading comes every five minutes
    static final Duration LEAD = Duration.ofMinutes(10);
    //no regeneration excuses a flow longer than this from its start (two hours and a margin)
    static final Duration LONGEST = Duration.ofHours(3);
    //a flow starting from BEFORE_SCHEDULE before the time of the regeneration to AFTER_SCHEDULE after it waits for a reading
    //CONFIRM after its start to tell whether the softener regenerates, HOLD_AT_MOST at most - the flow of the empty house
    //would otherwise be raised two minutes after the start, before the cloud says anything
    static final Duration BEFORE_SCHEDULE = Duration.ofMinutes(5);
    static final Duration AFTER_SCHEDULE = Duration.ofMinutes(20);
    static final Duration CONFIRM = Duration.ofMinutes(5);
    static final Duration HOLD_AT_MOST = Duration.ofMinutes(20);

    //how a flow that started at runStart is judged now: held - not yet, the softener may be regenerating; otherwise from
    //start, its own start or where the regeneration that took the water ended
    public record FlowStart(boolean held, LocalDateTime start) {

        public boolean excused(final LocalDateTime runStart) {
            return !held && !start.equals(runStart);
        }
    }

    //the reading of the status: regenerating null - not told; changedAt - when the cloud changed it, null when not known
    public Regeneration next(final Boolean regenerating, final LocalDateTime changedAt, final LocalTime scheduled,
                             final LocalDateTime at) {
        final LocalTime schedule = Optional.ofNullable(scheduled).orElse(scheduledAt);
        if (regenerating == null) {
            return new Regeneration(startedAt, endedAt, schedule, checkedAt);
        }
        final LocalDateTime when = changedAt != null && !changedAt.isAfter(at) ? changedAt : at;
        if (Boolean.TRUE.equals(regenerating) && (startedAt == null || endedAt != null && when.isAfter(endedAt))) {
            return new Regeneration(when, null, schedule, at);
        }
        if (Boolean.FALSE.equals(regenerating) && startedAt != null && endedAt == null) {
            return new Regeneration(startedAt, when.isBefore(startedAt) ? startedAt : when, schedule, at);
        }
        return new Regeneration(startedAt, endedAt, schedule, at);
    }

    public boolean regenerating() {
        return startedAt != null && endedAt == null;
    }

    public FlowStart flowStart(final LocalDateTime runStart, final LocalDateTime now) {
        if (startedAt != null && !runStart.isBefore(startedAt.minus(LEAD)) && runStart.isBefore(startedAt.plus(LONGEST))
            && (endedAt == null || !runStart.isAfter(endedAt))) {
            final LocalDateTime longest = startedAt.plus(LONGEST);
            if (endedAt == null) {
                return now.isBefore(longest) ? new FlowStart(true, runStart) : new FlowStart(false, longest);
            }
            return new FlowStart(false, endedAt.isBefore(longest) ? endedAt : longest);
        }
        final boolean scheduledNear = scheduledAt != null && Stream.of(-1, 0, 1)
            .map(days -> runStart.toLocalDate().plusDays(days).atTime(scheduledAt))
            .anyMatch(time -> !runStart.isBefore(time.minus(BEFORE_SCHEDULE)) && !runStart.isAfter(time.plus(AFTER_SCHEDULE)));
        final boolean told = checkedAt != null && !checkedAt.isBefore(runStart.plus(CONFIRM));
        if (scheduledNear && !told && now.isBefore(runStart.plus(HOLD_AT_MOST))) {
            return new FlowStart(true, runStart);
        }
        return new FlowStart(false, runStart);
    }
}
