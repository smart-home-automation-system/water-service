package cloud.cholewa.water.softener;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

//The softener in memory (HAS-397): the last reading and when it was taken, and how the reads go. reachable is null until
//the first pass ended, false after MISSED_LIMIT passes in a row brought nothing - or at once when the account has no
//softener - true otherwise. A failed read keeps the last reading; GET /softener says how old it is. Read by the history,
//the alerts and the live flow of the epic (HAS-398..401). One instance - the Deployment is Recreate.
//The regeneration (HAS-399) is held apart from the reading, for the flow alarms of the valve and the commands (HAS-400);
//every start and end is logged with the words of the cloud and regen_status_enum - those of a regeneration going on are
//not seen yet
@Slf4j
@Component
public class SoftenerTracker {

    static final int MISSED_LIMIT = 2;

    private final AtomicReference<State> state = new AtomicReference<>(State.START);
    private final AtomicReference<Regeneration> regeneration = new AtomicReference<>(Regeneration.NONE);
    private final AtomicReference<String> regenerationStatus = new AtomicReference<>();
    private static final Set<String> NOT_REGENERATING = Set.of("none", "scheduled");
    private static final int SCHEDULED = 1;

    //reading, measuredAt (the clock of the house) and readAt (the instant, for the age of the reading - a house time goes
    //back an hour in October) - null until the first reading; missed - passes in a row without a reading
    public record State(SoftenerReading reading, LocalDateTime measuredAt, Instant readAt, boolean passed, int missed) {

        static final State START = new State(null, null, null, false, 0);

        public Boolean reachable() {
            return passed ? missed < MISSED_LIMIT : null;
        }
    }

    public State current() {
        return state.get();
    }

    public Regeneration regeneration() {
        return regeneration.get();
    }

    //the words of the cloud for the regeneration, enriched_data.water_treatment.regeneration_status, as the last reading
    //had it (none, scheduled, ...) - null before a reading that had it
    public String regenerationStatus() {
        return regenerationStatus.get();
    }

    /**
     * The status of the regeneration from one reading (HAS-399, HAS-400). The words of the cloud decide when the reading has
     * them: none and scheduled are no regeneration, anything else is one. Without them regen_status_enum: 0 is none, 1 a
     * regeneration scheduled for the night (seen on 2026-11-09, set the minute it was scheduled), anything else a
     * regeneration - and only on the day of one, days_since_last_regen 0 (the cloud sets it at the start) or not told.
     * changedAt is the updated_at of the enum in the time of the house.
     */
    void regeneration(final String words, final Integer status, final Integer daysSince, final LocalDateTime changedAt,
                      final LocalTime scheduledAt, final LocalDateTime at) {
        final Boolean regenerating = Optional.ofNullable(words)
            .map(phase -> !NOT_REGENERATING.contains(phase.trim().toLowerCase(Locale.ROOT)))
            .orElseGet(() -> status == null ? null
                : status > SCHEDULED && (daysSince == null || daysSince == 0));
        Optional.ofNullable(words).ifPresent(regenerationStatus::set);
        final Regeneration before = regeneration.get();
        final Regeneration after = regeneration.updateAndGet(held -> held.next(regenerating, changedAt, scheduledAt, at));
        if (after.regenerating() && !before.regenerating()) {
            log.info("The softener regenerates since {} ({}, regen_status_enum {})", after.startedAt(), words, status);
        } else if (!after.regenerating() && before.regenerating()) {
            log.info("The regeneration of the softener ended at {} ({}, regen_status_enum {})", after.endedAt(), words,
                status);
        }
    }

    void read(final SoftenerReading reading, final LocalDateTime at, final Instant instant) {
        state.set(new State(reading, at, instant, true, 0));
    }

    void missed() {
        state.updateAndGet(held -> new State(held.reading(), held.measuredAt(), held.readAt(), true, held.missed() + 1));
    }

    //the account has no softener the service may read: unreachable at once, the last reading kept
    void noSoftener() {
        state.updateAndGet(held -> new State(held.reading(), held.measuredAt(), held.readAt(), true,
            Math.max(held.missed() + 1, MISSED_LIMIT)));
    }
}
